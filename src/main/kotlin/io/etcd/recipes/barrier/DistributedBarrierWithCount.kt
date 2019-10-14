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
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.time.Conversions.Companion.timeUnitToDuration
import com.sudothought.common.util.randomId
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.*
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
class DistributedBarrierWithCount(val urls: List<String>,
                                  val barrierPath: String,
                                  val memberCount: Int,
                                  val clientId: String) : EtcdConnector(urls), Closeable {

    constructor(urls: List<String>,
                barrierPath: String,
                memberCount: Int) : this(urls, barrierPath, memberCount, "Client:${randomId(7)}")

    private val readyPath = barrierPath.appendToPath("ready")
    private val waitingPath = barrierPath.appendToPath("waiting")

    init {
        require(urls.isNotEmpty()) { "URL cannot be empty" }
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
        val uniqueToken = "$clientId:${randomId(7)}"
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
                        Then(deleteOp(readyPath))
                    }
                }
            }
        }

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(readyPath.doesNotExist)
            Then(readyPath setTo uniqueToken)
        }

        val lease = leaseClient.grant(2).get()

        val txn =
            kvClient.transaction {
                If(waitingPath.doesNotExist)
                Then(waitingPath.setTo(uniqueToken, lease.asPutOption))
            }

        when {
            !txn.isSucceeded                                        -> throw EtcdRecipeException("Failed to set waitingPath")
            kvClient.getValue(waitingPath)?.asString != uniqueToken -> throw EtcdRecipeException("Failed to assign waitingPath unique value")
            else                                                    -> {
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
        }
    }

    override fun close() {
        semaphore.withLock {
            super.close()
        }
    }

    companion object {
        @JvmStatic
        fun delete(urls: List<String>, barrierPath: String) {
            require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }

            connectToEtcd(urls) { client ->
                    client.withKvClient { kvClient ->
                        // Delete all children
                        kvClient.getKeys(barrierPath).forEach { kvClient.delete(it) }
                    }
                }
        }
    }
}