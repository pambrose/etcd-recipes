/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.election

import com.github.pambrose.common.concurrent.BooleanMonitor
import com.github.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.github.pambrose.common.time.timeUnitToDuration
import com.github.pambrose.common.util.randomId
import com.github.pambrose.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import io.etcd.recipes.barrier.DistributedDoubleBarrier.Companion.defaultClientId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdConnector.Companion.defaultTtlSecs
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.getChildrenValues
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.keepAliveWith
import io.etcd.recipes.common.leaseGrant
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import mu.KLogging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.seconds

@JvmOverloads
fun <T> withLeaderSelector(client: Client,
                           electionPath: String,
                           listener: LeaderSelectorListener,
                           leaseTtlSecs: Long = defaultTtlSecs,
                           userExecutor: Executor? = null,
                           clientId: String = defaultClientId(),
                           receiver: LeaderSelector.() -> T): T =
    LeaderSelector(client, electionPath, listener, leaseTtlSecs, userExecutor, clientId).use { it.receiver() }

@JvmOverloads
fun <T> withLeaderSelector(client: Client,
                           electionPath: String,
                           takeLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
                           relinquishLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
                           leaseTtlSecs: Long = defaultTtlSecs,
                           executorService: ExecutorService? = null,
                           clientId: String = defaultClientId(),
                           receiver: LeaderSelector.() -> T): T =
    LeaderSelector(client,
                   electionPath,
                   takeLeadershipBlock,
                   relinquishLeadershipBlock,
                   leaseTtlSecs,
                   executorService,
                   clientId).use { it.receiver() }

// For Java clients
class LeaderSelector
@JvmOverloads
constructor(client: Client,
            val electionPath: String,
            private val listener: LeaderSelectorListener,
            val leaseTtlSecs: Long = defaultTtlSecs,
            private val userExecutor: Executor? = null,
            val clientId: String = defaultClientId()) : EtcdConnector(client) {

    // For Kotlin clients
    @JvmOverloads
    constructor(client: Client,
                electionPath: String,
                takeLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
                relinquishLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
                leaseTtlSecs: Long = defaultTtlSecs,
                executorService: ExecutorService? = null,
                clientId: String = defaultClientId()) : this(client,
                                                             electionPath,
                                                             object : LeaderSelectorListener {
                                                                 override fun takeLeadership(selector: LeaderSelector) {
                                                                     takeLeadershipBlock.invoke(selector)
                                                                 }

                                                                 override fun relinquishLeadership(selector: LeaderSelector) {
                                                                     relinquishLeadershipBlock.invoke(selector)
                                                                 }
                                                             },
                                                             leaseTtlSecs,
                                                             executorService,
                                                             clientId)

    private val executor = userExecutor ?: Executors.newFixedThreadPool(3)
    private val terminateWatch = BooleanMonitor(false)
    private val terminateKeepAlive = BooleanMonitor(false)
    private val leadershipComplete = BooleanMonitor(false)
    private val attemptLeadership = BooleanMonitor(true)
    private var electedLeader by atomicBoolean(false)
    private var startCallAllowed by atomicBoolean(true)
    private val leaderPath = electionPath.withLeaderSuffix

    init {
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
        require(leaseTtlSecs > 0) { "Lease TTL must be > 0" }
    }

    val isLeader get() = electedLeader

    val isFinished get() = leadershipComplete.get()

    fun start(): LeaderSelector {

        val electionSetup = BooleanMonitor(false)

        synchronized(startCallAllowed) {
            if (!startCallAllowed)
                throw EtcdRecipeRuntimeException("Previous call to start() not complete")

            checkCloseNotCalled()

            terminateWatch.set(false)
            terminateKeepAlive.set(false)
            leadershipComplete.set(false)
            startThreadComplete.set(false)
            attemptLeadership.set(true)
            startCalled = true
            closeCalled = false
            electedLeader = false
            startCallAllowed = false
        }

        executor.execute {
            try {
                val watchStarted = BooleanMonitor(false)
                val watchComplete = BooleanMonitor(false)
                val advertiseComplete = BooleanMonitor(false)

                executor.execute {
                    try {
                        val watchOption = watchOption { withNoPut(true) }
                        client.withWatcher(leaderPath,
                                           watchOption,
                                           { watchResponse ->
                                               for (event in watchResponse.events)
                                                   if (event.eventType == DELETE) {
                                                       // Run for leader whenever leader key is deleted
                                                       attemptToBecomeLeader(client)
                                                   }
                                           }) {
                            watchStarted.set(true)
                            terminateWatch.waitUntilTrue()
                        }
                    } catch (e: Throwable) {
                        logger.error(e) { "In withWatchClient()" }
                        exceptionList.value += e
                    } finally {
                        watchComplete.set(true)
                    }
                }

                executor.execute {
                    try {
                        advertiseParticipation()
                    } catch (e: Throwable) {
                        logger.error(e) { "In advertiseParticipation()" }
                        exceptionList.value += e
                    } finally {
                        advertiseComplete.set(true)
                    }
                }

                // Wait for the watcher to start
                watchStarted.waitUntilTrue()

                electionSetup.set(true)

                // Clients should run for leader in case they are the first to run
                attemptToBecomeLeader(client)

                leadershipComplete.waitUntilTrue()
                watchComplete.waitUntilTrue()
                advertiseComplete.waitUntilTrue()
            } catch (e: Throwable) {
                logger.error(e) { "In start()" }
                exceptionList.value += e
            } finally {
                startThreadComplete.set(true)
            }
        }

        electionSetup.waitUntilTrue()

        return this
    }

    @Throws(InterruptedException::class)
    fun waitOnLeadershipComplete(): Boolean = waitOnLeadershipComplete(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class)
    fun waitOnLeadershipComplete(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnLeadershipComplete(timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class)
    fun waitOnLeadershipComplete(timeout: Duration): Boolean {
        checkStartCalled()
        checkCloseNotCalled()
        // Check startThreadComplete here in case start() was re-used without a call to close()
        startThreadComplete.waitUntilTrue()
        return leadershipComplete.waitUntilTrue(timeout)
    }

    private fun markLeadershipComplete() {
        terminateWatch.set(true)
        terminateKeepAlive.set(true)
        leadershipComplete.set(true)
    }

    @Synchronized
    override fun close() {
        if (closeCalled)
            return

        checkStartCalled()

        markLeadershipComplete()
        startThreadComplete.waitUntilTrue()

        if (userExecutor == null) (executor as ExecutorService).shutdown()

        super.close()
    }

    @Throws(EtcdRecipeException::class)
    private fun advertiseParticipation() {
        val path = electionPath.withParticipationSuffix.appendToPath(clientId)

        // Wait until key goes away when previous keep alive finishes
        val attemptCount = leaseTtlSecs * 2
        for (i in 0 until attemptCount) {
            if (!client.isKeyPresent(path))
                break

            if (i == attemptCount - 1)
                logger.error { "Exhausted wait for deletion of participation key $path" }

            sleep(1.seconds)
        }

        // Prime lease with leaseTtlSecs seconds to give keepAlive a chance to get started
        val lease = client.leaseGrant(leaseTtlSecs.seconds)
        val txn =
            client.transaction {
                If(path.doesNotExist)
                Then(path.setTo(clientId, putOption { withLeaseId(lease.id) }))
            }

        if (!txn.isSucceeded)
            throw EtcdRecipeException("Participation registration failed [$path]")

        // Run keep-alive until closed
        client.keepAliveWith(lease) { terminateKeepAlive.waitUntilTrue() }
    }

    // This will not return until election failure or leader surrenders leadership after being elected
    @Synchronized
    private fun attemptToBecomeLeader(client: Client): Boolean {
        if (isLeader || !attemptLeadership.get())
            return false

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$clientId:${randomId(tokenLength)}"

        // Prime lease to give keepAliveWith a chance to get started
        val lease = client.leaseClient.grant(leaseTtlSecs).get()

        // Check the key name. If it is not found, then set it
        val txn =
            client.transaction {
                If(leaderPath.doesNotExist)
                Then(leaderPath.setTo(uniqueToken, putOption { withLeaseId(lease.id) }))
            }

        // Check to see if unique value was successfully set in the CAS step
        if (isLeader || !txn.isSucceeded || client.getValue(leaderPath)?.asString != uniqueToken) {
            // Failed to become leader
            return false
        }

        // Selected as leader. This will exit when leadership is relinquished
        try {
            client.keepAliveWith(lease) {
                electedLeader = true
                listener.takeLeadership(this)
            }
            // Leadership was relinquished
            listener.relinquishLeadership(this)
            return true
        } catch (e: Throwable) {
            logger.error(e) { "In attemptToBecomeLeader()" }
            exceptionList.value += e
            return false
        } finally {
            // Do this after leadership is complete so the thread does not terminate
            attemptLeadership.set(false)
            startCallAllowed = true
            electedLeader = false
            markLeadershipComplete()
        }
    }

    companion object : KLogging() {

        private val String.withParticipationSuffix get() = appendToPath("participants")
        private val String.withLeaderSuffix get() = appendToPath("LEADER")

        internal val String.stripUniqueSuffix get() = dropLast(tokenLength + 1)

        internal fun defaultClientId() = "${LeaderSelector::class.simpleName}:${randomId(tokenLength)}"

        @JvmStatic
        fun getParticipants(client: Client, electionPath: String): List<Participant> {

            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }

            val participants = mutableListOf<Participant>()
            val leader = client.getValue(electionPath.withLeaderSuffix)?.asString?.stripUniqueSuffix ?: ""
            client.getChildrenValues(electionPath.withParticipationSuffix).map { it.asString }
                .forEach { participants += Participant(it, leader == it) }
            return participants
        }

        @JvmStatic
        fun reportLeader(urls: List<String>,
                         electionPath: String,
                         listener: LeaderListener,
                         executor: Executor): CountDownLatch {

            require(urls.isNotEmpty()) { "URLs cannot be empty" }
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }

            val terminateListener = CountDownLatch(1)
            executor.execute {
                connectToEtcd(urls) { client ->
                    client.withWatcher(electionPath.withLeaderSuffix,
                                       block = { watchResponse ->
                                           for (event in watchResponse.events)
                                               try {
                                                   when (event.eventType) {
                                                       PUT          -> listener.takeLeadership(event.keyValue.value.asString.stripUniqueSuffix)
                                                       DELETE       -> listener.relinquishLeadership()
                                                       UNRECOGNIZED -> logger.error { "Unrecognized error with $electionPath watch" }
                                                       else         -> logger.error { "Unknown error with $electionPath watch" }
                                                   }
                                               } catch (e: Throwable) {
                                                   logger.error(e) { "Exception in reportLeader()" }
                                               }
                                       }) {
                        terminateListener.await()
                    }
                }
            }
            return terminateListener
        }
    }
}