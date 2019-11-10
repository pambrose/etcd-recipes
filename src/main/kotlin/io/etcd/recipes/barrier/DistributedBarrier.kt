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

import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.delegate.AtomicDelegates.nullableReference
import com.sudothought.common.time.timeUnitToDuration
import com.sudothought.common.util.randomId
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.grant
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.keepAlive
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watcher
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.seconds

class DistributedBarrier
@JvmOverloads
constructor(urls: List<String>,
            val barrierPath: String,
            val leaseTtlSecs: Long = defaultTtlSecs,
            private val waitOnMissingBarriers: Boolean = true,
            val clientId: String = defaultClientId()) : EtcdConnector(urls), Closeable {

    private var keepAliveLease by nullableReference<CloseableClient?>(null)
    private var barrierRemoved by atomicBoolean(false)

    init {
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
    }

    fun isBarrierSet(): Boolean {
        checkCloseNotCalled()
        return kvClient.isKeyPresent(barrierPath)
    }

    @Synchronized
    fun setBarrier(): Boolean {
        checkCloseNotCalled()

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$clientId:${randomId(tokenLength)}"

        return if (kvClient.isKeyPresent(barrierPath))
            false
        else {
            // Prime lease with 2 seconds to give keepAlive a chance to get started
            val lease = leaseClient.grant(leaseTtlSecs.seconds).get()

            // Do a CAS on the key name. If it is not found, then set it
            val txn =
                kvClient.transaction {
                    If(barrierPath.doesNotExist)
                    Then(barrierPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
                }

            // Check to see if unique value was successfully set in the CAS step
            if (txn.isSucceeded && kvClient.getValue(barrierPath)?.asString == uniqueToken) {
                keepAliveLease = leaseClient.keepAlive(lease)
                true
            } else {
                false
            }
        }
    }

    @Synchronized
    fun removeBarrier(): Boolean {
        checkCloseNotCalled()
        return if (barrierRemoved) {
            false
        } else {
            keepAliveLease?.close()
            keepAliveLease = null

            kvClient.delete(barrierPath)

            barrierRemoved = true

            true
        }
    }

    @Throws(InterruptedException::class)
    fun waitOnBarrier(): Boolean = waitOnBarrier(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class)
    fun waitOnBarrier(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnBarrier(timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class)
    fun waitOnBarrier(timeout: Duration): Boolean {
        checkCloseNotCalled()

        // Check if barrier is present before using watcher
        if (!waitOnMissingBarriers && !isBarrierSet())
            return true

        val waitLatch = CountDownLatch(1)

        return watchClient.watcher(barrierPath) { watchResponse ->
            watchResponse.events
                .forEach { watchEvent ->
                    if (watchEvent.eventType == DELETE)
                        waitLatch.countDown()
                }

        }.use {
            // Check one more time in case watch missed the delete just after last check
            if (!waitOnMissingBarriers && !isBarrierSet())
                waitLatch.countDown()

            waitLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    override fun close() {
        if (closeCalled)
            return

        keepAliveLease?.close()
        keepAliveLease = null

        super.close()
    }

    companion object : KLogging() {
        private fun defaultClientId() = "${DistributedBarrier::class.simpleName}:${randomId(tokenLength)}"
    }
}