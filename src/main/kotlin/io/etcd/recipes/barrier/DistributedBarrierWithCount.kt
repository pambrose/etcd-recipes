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

package io.etcd.recipes.barrier

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.time.timeUnitToDuration
import com.sudothought.common.util.randomId
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.ensureSuffix
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getChildrenCount
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.grant
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.keepAlive
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.seconds

/*
    First node creates subnode /ready
    Each node creates its own subnode with keepalive on it
    Each node creates a watch for DELETE on /ready and PUT on any waiter
    Query the number of children after each PUT on waiter and DELETE /ready if memberCount seen
    Leave if DELETE of /ready is seen
*/
class DistributedBarrierWithCount
@JvmOverloads
constructor(val urls: List<String>,
            val barrierPath: String,
            val memberCount: Int,
            val leaseTtlSecs: Long = defaultTtlSecs,
            val clientId: String = defaultClientId()) : EtcdConnector(urls), Closeable {

    private val readyPath = barrierPath.appendToPath("ready")
    private val waitingPath = barrierPath.appendToPath("waiting")

    init {
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
            return kvClient.getChildrenCount(waitingPath)
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
        val uniqueToken = "$clientId:${randomId(tokenLength)}"
        val waitingPath = waitingPath.appendToPath(uniqueToken)

        checkCloseNotCalled()

        fun closeKeepAlive() {
            if (!keepAliveClosed.get()) {
                keepAliveLease?.close()
                keepAliveLease = null
                kvClient.value.delete(waitingPath)
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
                        If(readyPath.doesExist)
                        Then(deleteKey(readyPath))
                    }
                }
            }
        }

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(readyPath.doesNotExist)
            Then(readyPath setTo uniqueToken)
        }

        val lease = leaseClient.grant(leaseTtlSecs.seconds).get()

        val txn =
            kvClient.transaction {
                If(waitingPath.doesNotExist)
                Then(waitingPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
            }

        when {
            !txn.isSucceeded                                        -> throw EtcdRecipeException("Failed to set waitingPath")
            kvClient.getValue(waitingPath)?.asString != uniqueToken -> throw EtcdRecipeException("Failed to assign waitingPath unique value")
            else                                                    -> {
                // Keep key alive
                keepAliveLease = leaseClient.keepAlive(lease)

                checkWaiterCount()

                // Do not bother starting watcher if latch is already done
                return if (keepAliveClosed.get()) {
                    true
                } else {
                    // Watch for DELETE of /ready and PUTS on /waiters/*
                    val trailingKey = barrierPath.ensureSuffix("/")
                    val watchOption = watchOption { withPrefix(trailingKey.asByteSequence) }
                    watchClient.watcher(trailingKey, watchOption) { watchResponse ->
                        watchResponse.events
                            .forEach { watchEvent ->
                                val key = watchEvent.keyValue.key.asString
                                when {
                                    key.startsWith(waitingPath) && watchEvent.eventType == PUT  -> checkWaiterCount()
                                    key.startsWith(readyPath) && watchEvent.eventType == DELETE -> closeKeepAlive()
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
            }
        }
    }

    companion object {
        private fun defaultClientId() = "${DistributedBarrierWithCount::class.simpleName}:${randomId(tokenLength)}"

        @JvmStatic
        fun delete(urls: List<String>, barrierPath: String) {

            require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }

            etcdExec(urls) { _, kvClient ->
                // Delete all children
                kvClient.getChildrenKeys(barrierPath).forEach { kvClient.delete(it) }
            }
        }
    }
}