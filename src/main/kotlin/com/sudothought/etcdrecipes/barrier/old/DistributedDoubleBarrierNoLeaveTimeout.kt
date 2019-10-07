/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package com.sudothought.etcdrecipes.barrier.old

import com.sudothought.common.concurrent.isFinished
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.delegate.AtomicDelegates.nonNullableReference
import com.sudothought.common.delegate.AtomicDelegates.nullableReference
import com.sudothought.common.time.Conversions.Static.timeUnitToDuration
import com.sudothought.common.util.randomId
import com.sudothought.etcdrecipes.common.EtcdConnector
import com.sudothought.etcdrecipes.common.EtcdRecipeException
import com.sudothought.etcdrecipes.jetcd.*
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class DistributedDoubleBarrierNoLeaveTimeout(val url: String,
                                             val barrierPath: String,
                                             val memberCount: Int,
                                             val clientId: String
) : EtcdConnector(url), Closeable {

    constructor(url: String,
                barrierPath: String,
                memberCount: Int) : this(url, barrierPath, memberCount, "Client:${randomId(9)}")

    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private var enterCalled by atomicBoolean(false)
    private var leaveCalled by atomicBoolean(false)
    private var watcher by nullableReference<Watch.Watcher?>()
    private var waitingPath by nonNullableReference<String>()
    private val enterWaitLatch = CountDownLatch(1)
    private val keepAliveLatch = CountDownLatch(1)
    private val leaveLatch = CountDownLatch(1)
    private val readyPath = barrierPath.appendToPath("ready")
    private val waitingPrefix = barrierPath.appendToPath("waiting")

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
    }

    private val isReadySet: Boolean get() = kvClient.isKeyPresent(readyPath)

    val waiterCount: Long get() = kvClient.count(waitingPrefix)

    @Throws(EtcdRecipeException::class)
    fun enter(): Boolean = enter(Long.MAX_VALUE.days)

    @Throws(EtcdRecipeException::class)
    fun enter(timeout: Long, timeUnit: TimeUnit): Boolean = enter(timeUnitToDuration(timeout, timeUnit))

    @Throws(EtcdRecipeException::class)
    fun enter(timeout: Duration): Boolean {

        val uniqueToken = "$clientId:${randomId(9)}"

        enterCalled = true

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(equalTo(readyPath, CmpTarget.version(0)))
            Then(putOp(readyPath, uniqueToken))
        }

        waitingPath = "$waitingPrefix/$uniqueToken"
        val lease = leaseClient.grant(2).get()

        val txn =
            kvClient.transaction {
                If(equalTo(waitingPath, CmpTarget.version(0)))
                Then(putOp(waitingPath, uniqueToken, lease.asPutOption))
            }

        if (!txn.isSucceeded)
            throw EtcdRecipeException("Failed to set waitingPath")
        if (kvClient.getStringValue(waitingPath) != uniqueToken)
            throw EtcdRecipeException("Failed to assign waitingPath unique value")

        // Keep key alive
        executor.value.submit { leaseClient.keepAliveWith(lease) { keepAliveLatch.await() } }

        fun checkWaiterCountInEnter() {
            // First see if /ready is missing
            if (!isReadySet) {
                enterWaitLatch.countDown()
            } else {
                if (waiterCount >= memberCount) {

                    enterWaitLatch.countDown()

                    // Delete /ready key
                    kvClient.transaction {
                        If(equalTo(readyPath, CmpTarget.version(0)))
                        Then()
                        Else(deleteOp(readyPath))
                    }
                }
            }
        }

        checkWaiterCountInEnter()

        // Do not bother starting watcher if latch is already done
        if (enterWaitLatch.isFinished)
            return true

        // Watch for DELETE of /ready and PUTS on /waiters/*
        val adjustedKey = barrierPath.ensureTrailing("/")
        val watchOption = WatchOption.newBuilder().withPrefix(adjustedKey.asByteSequence).build()
        watcher =
            watchClient.watcher(adjustedKey, watchOption) { watchResponse ->
                watchResponse.events
                    .forEach { watchEvent ->
                        val key = watchEvent.keyValue.key.asString
                        when {
                            // enter() events
                            key.startsWith(readyPath) && watchEvent.eventType == DELETE -> enterWaitLatch.countDown()
                            key.startsWith(waitingPrefix) && watchEvent.eventType == PUT -> checkWaiterCountInEnter()
                            // leave() events
                            key.startsWith(waitingPrefix) && watchEvent.eventType == DELETE -> checkWaiterCountInLeave()
                        }
                    }
            }

        // Check one more time in case watch missed the delete just after last check
        checkWaiterCountInEnter()

        val success = enterWaitLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
        // Cleanup if a time-out occurred
        if (!success)
            enterWaitLatch.countDown() // Release keep-alive waiting on latch.

        return success
    }

    private fun checkWaiterCountInLeave() {
        if (waiterCount == 0L) {
            keepAliveLatch.countDown()
            leaveLatch.countDown()
        }
    }

    fun leave(): Boolean = leave(Long.MAX_VALUE.days)

    fun leave(timeout: Long, timeUnit: TimeUnit): Boolean = leave(timeUnitToDuration(timeout, timeUnit))

    @Throws(EtcdRecipeException::class)
    fun leave(timeout: Duration): Boolean {
        if (!enterCalled) throw EtcdRecipeException("enter() must be called before leave()")

        leaveCalled = true

        // println("Deleting ${waitingPath.get()}")
        kvClient.delete(waitingPath)

        checkWaiterCountInLeave()

        return leaveLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        watcher?.close()

        super.close()

        if (executor.isInitialized())
            executor.value.shutdown()
    }

    companion object {
        fun reset(url: String, barrierPath: String) {
            require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        // Delete all children
                        kvClient.getChildrenKeys(barrierPath).forEach { kvClient.delete(it) }
                    }
                }
        }
    }
}