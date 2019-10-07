/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package com.sudothought.etcdrecipes.barrier

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.time.Conversions.Static.timeUnitToDuration
import com.sudothought.common.util.randomId
import com.sudothought.etcdrecipes.common.EtcdConnector
import com.sudothought.etcdrecipes.common.EtcdRecipeException
import com.sudothought.etcdrecipes.jetcd.*
import io.etcd.jetcd.Client
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

/*
    First node creates subnode /ready
    Each node creates its own subnode with keepalive on it
    Each node creates a watch for DELETE on /ready and PUT on any waiter
    Query the number of children after each PUT on waiter and DELETE /ready if memberCount seen
    Leave if DELETE of /ready is seen
*/
class DistributedBarrierWithCount(val url: String,
                                  val barrierPath: String,
                                  val memberCount: Int,
                                  val clientId: String) : EtcdConnector(url), Closeable {

    constructor(url: String,
                barrierPath: String,
                memberCount: Int) : this(url, barrierPath, memberCount, "Client:${randomId(9)}")

    private val readyPath = barrierPath.appendToPath("ready")
    private val waitingPath = barrierPath.appendToPath("waiting")

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
    }

    private val isReadySet: Boolean
        get() {
            checkCloseNotCalled()
            return kvClient.isKeyPresent(readyPath)
        }

    val waiterCount: Long
        get() {
            checkCloseNotCalled()
            return kvClient.count(waitingPath)
        }

    @Throws(InterruptedException::class, EtcdRecipeException::class)
    fun waitOnBarrier(): Boolean = waitOnBarrier(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class, EtcdRecipeException::class)
    fun waitOnBarrier(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnBarrier(timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class, EtcdRecipeException::class)
    fun waitOnBarrier(timeout: Duration): Boolean {
        var keepAliveLease: CloseableClient? = null
        val keepAliveClosed = BooleanMonitor(false)
        val uniqueToken = "$clientId:${randomId(9)}"

        checkCloseNotCalled()

        fun closeKeepAlive() {
            if (!keepAliveClosed.get()) {
                keepAliveLease?.close()
                keepAliveClosed.set(true)
            }
        }

        fun checkWaiterCount() {
            // First see if /ready is missing
            if (!isReadySet) {
                closeKeepAlive()
            } else {
                if (waiterCount >= memberCount) {

                    closeKeepAlive()

                    // Delete /ready key
                    kvClient.transaction {
                        If(equalTo(readyPath, CmpTarget.version(0)))
                        Then()
                        Else(deleteOp(readyPath))
                    }
                }
            }
        }

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(equalTo(readyPath, CmpTarget.version(0)))
            Then(putOp(readyPath, uniqueToken))
        }

        val waitingPath = waitingPath.appendToPath(uniqueToken)
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
        keepAliveLease = leaseClient.keepAlive(lease)

        checkWaiterCount()

        // Do not bother starting watcher if latch is already done
        if (keepAliveClosed.get())
            return true

        // Watch for DELETE of /ready and PUTS on /waiters/*
        val adjustedKey = barrierPath.ensureTrailing("/")
        val watchOption = WatchOption.newBuilder().withPrefix(adjustedKey.asByteSequence).build()

        return watchClient.watcher(adjustedKey, watchOption) { watchResponse ->
            watchResponse.events
                .forEach { watchEvent ->
                    val key = watchEvent.keyValue.key.asString
                    when {
                        key.startsWith(this.waitingPath) && watchEvent.eventType == PUT -> checkWaiterCount()
                        key.startsWith(readyPath) && watchEvent.eventType == DELETE     -> closeKeepAlive()
                    }
                }

        }.use {
            // Check one more time in case watch missed the delete just after last check
            checkWaiterCount()

            val success = keepAliveClosed.waitUntilTrue(timeout)
            // Cleanup if a time-out occurred
            if (!success) {
                closeKeepAlive()
                kvClient.delete(waitingPath)  // This is redundant but waiting for keep-alive to stop is slower
            }

            success
        }
    }

    override fun close() {
        semaphore.withLock {
            super.close()
        }
    }

    companion object Static {
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