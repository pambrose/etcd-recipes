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

package io.etcd.recipes.election

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.time.Conversions.Companion.timeUnitToDuration
import com.sudothought.common.util.randomId
import com.sudothought.common.util.sleep
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Watch
import io.etcd.jetcd.watch.WatchEvent.EventType.*
import io.etcd.recipes.common.*
import mu.KLogging
import java.io.Closeable
import java.util.*
import java.util.concurrent.*
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.seconds


// For Java clients
class LeaderSelector(val urls: List<String>,
                     val electionPath: String,
                     private val listener: LeaderSelectorListener,
                     val clientId: String,
                     private val userExecutor: ExecutorService?) : Closeable {

    // For Java clients
    constructor(urls: List<String>,
                electionPath: String,
                listener: LeaderSelectorListener) : this(urls,
                                                         electionPath,
                                                         listener,
                                                         "Client:${randomId(7)}",
                                                         null)

    // For Java clients
    constructor(urls: List<String>,
                electionPath: String,
                listener: LeaderSelectorListener,
                clientId: String) : this(urls,
                                         electionPath,
                                         listener,
                                         clientId,
                                         null)

    // For Java clients
    constructor(urls: List<String>,
                electionPath: String,
                listener: LeaderSelectorListener,
                executorService: ExecutorService) : this(urls,
                                                         electionPath,
                                                         listener,
                                                         "Client:${randomId(7)}",
                                                         executorService)

    // For Kotlin clients
    constructor(urls: List<String>,
                electionPath: String,
                takeLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
                relinquishLeadershipBlock: (selector: LeaderSelector) -> Unit = {},
                clientId: String = "Client:${randomId(7)}",
                executorService: ExecutorService? = null) :
            this(urls,
                 electionPath,
                 object : LeaderSelectorListener {
                     override fun takeLeadership(selector: LeaderSelector) {
                         takeLeadershipBlock.invoke(selector)
                     }

                     override fun relinquishLeadership(selector: LeaderSelector) {
                         relinquishLeadershipBlock.invoke(selector)
                     }
                 },
                 clientId,
                 executorService)

    private val closeSemaphore = Semaphore(1, true)
    private val executor = userExecutor ?: Executors.newFixedThreadPool(3)
    private val terminateWatch = BooleanMonitor(false)
    private val terminateKeepAlive = BooleanMonitor(false)
    private val leadershipComplete = BooleanMonitor(false)
    private val startThreadComplete = BooleanMonitor(false)
    private val attemptLeadership = BooleanMonitor(true)
    private var startCalled by atomicBoolean(false)
    private var closeCalled by atomicBoolean(false)
    private var electedLeader by atomicBoolean(false)
    private var startCallAllowed by atomicBoolean(true)
    private val leaderPath = leaderPath(electionPath)
    private val exceptionList = Collections.synchronizedList(mutableListOf<Throwable>())

    init {
        require(urls.isNotEmpty()) { "URLs cannot be empty" }
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    val isLeader get() = electedLeader

    val isFinished get() = leadershipComplete.get()

    fun start(): LeaderSelector {

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

        val connectedToEtcd = BooleanMonitor(false)

        executor.execute {
            try {
                connectToEtcd(urls) { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withKvClient { kvClient ->
                            connectedToEtcd.set(true)
                            val leaderSemaphore = Semaphore(1, true)
                            val watchStarted = BooleanMonitor(false)
                            val watchStopped = BooleanMonitor(false)
                            val advertiseComplete = BooleanMonitor(false)

                            executor.execute {
                                try {
                                    client.withWatchClient { watchClient ->
                                        // Run for leader whenever leader key is deleted
                                        watchForDeleteEvents(watchClient, watchStarted) {
                                            leaderSemaphore.withLock {
                                                attemptToBecomeLeader(leaseClient, kvClient)
                                            }
                                        }.use {
                                            terminateWatch.waitUntilTrue()
                                        }
                                        watchStopped.set(true)
                                    }
                                } catch (e: Throwable) {
                                    logger.error(e) { "In withWatchClient()" }
                                    exceptionList += e
                                }
                            }

                            executor.execute {
                                try {
                                    advertiseParticipation(leaseClient, kvClient)
                                } catch (e: Throwable) {
                                    logger.error(e) { "In advertiseParticipation()" }
                                    exceptionList += e
                                } finally {
                                    advertiseComplete.set(true)
                                }
                            }

                            // Wait for the watcher to start
                            watchStarted.waitUntilTrue()

                            // Clients should run for leader in case they are the first to run
                            leaderSemaphore.withLock { attemptToBecomeLeader(leaseClient, kvClient) }

                            leadershipComplete.waitUntilTrue()
                            watchStopped.waitUntilTrue()
                            advertiseComplete.waitUntilTrue()
                        }
                    }
                }
            } catch (e: Throwable) {
                logger.error(e) { "In start()" }
                exceptionList += e
            } finally {
                startThreadComplete.set(true)
            }
        }

        connectedToEtcd.waitUntilTrue()
        return this
    }

    val backgroundExceptions get() = exceptionList

    val hasBackgroundExceptions get() = exceptionList.size > 0

    fun clearBackgroundExceptions() = exceptionList.clear()

    @Throws(InterruptedException::class)
    fun waitOnLeadershipComplete(): Boolean = waitOnLeadershipComplete(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class)
    fun waitOnLeadershipComplete(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnLeadershipComplete(timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class)
    fun waitOnLeadershipComplete(timeout: Duration): Boolean {
        checkStartCalled()
        checkCloseNotCalled()
        return leadershipComplete.waitUntilTrue(timeout)
    }

    private fun markLeadershipComplete() {
        terminateWatch.set(true)
        terminateKeepAlive.set(true)
        leadershipComplete.set(true)
    }

    private fun checkStartCalled() {
        if (!startCalled) throw EtcdRecipeRuntimeException("start() not called")
    }

    private fun checkCloseNotCalled() {
        if (closeCalled) throw EtcdRecipeRuntimeException("close() already closed")
    }

    override fun close() {
        closeSemaphore.withLock {
            checkStartCalled()

            if (!closeCalled) {
                closeCalled = true
                markLeadershipComplete()
                startThreadComplete.waitUntilTrue()
                if (userExecutor == null) executor.shutdown()
            }
        }
    }

    private fun watchForDeleteEvents(watchClient: Watch,
                                     watchStarted: BooleanMonitor,
                                     block: () -> Unit): Watch.Watcher {
        val watcher =
            watchClient.watcher(leaderPath) { watchResponse ->
                watchResponse.events.forEach { event ->
                    if (event.eventType == DELETE) block.invoke()
                }
            }
        watchStarted.set(true)
        return watcher
    }

    @Throws(EtcdRecipeException::class)
    private fun advertiseParticipation(leaseClient: Lease, kvClient: KV) {
        val path = participationPath(electionPath).appendToPath(clientId)

        // Wait until key goes away when previous keep alive finishes
        for (i in (0..10)) {
            if (!kvClient.isKeyPresent(path))
                break
            sleep(1.seconds)
        }

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()
        val txn =
            kvClient.transaction {
                If(path.doesNotExist)
                Then(path.setTo(clientId, lease.asPutOption))
            }

        if (!txn.isSucceeded) throw EtcdRecipeException("Participation registration failed [$path]")

        // Run keep-alive until closed
        leaseClient.keepAliveWith(lease) { terminateKeepAlive.waitUntilTrue() }
    }

    // This will not return until election failure or leader surrenders leadership after being elected
    private fun attemptToBecomeLeader(leaseClient: Lease, kvClient: KV): Boolean {
        if (isLeader || !attemptLeadership.get()) return false

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$clientId:${randomId(uniqueSuffixLength)}"

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()

        // Do a CAS on the key name. If it is not found, then set it
        val txn =
            kvClient.transaction {
                If(leaderPath.doesNotExist)
                Then(leaderPath.setTo(uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (!isLeader && txn.isSucceeded && kvClient.getValue(leaderPath)?.asString == uniqueToken) {
            // Selected as leader. This will exit when leadership is relinquished
            leaseClient.keepAliveWith(lease) {
                electedLeader = true
                listener.takeLeadership(this)
            }

            // Leadership was relinquished
            listener.relinquishLeadership(this)

            // Do this after leadership is complete so the thread does not terminate
            attemptLeadership.set(false)
            startCallAllowed = true
            electedLeader = false
            markLeadershipComplete()
            true
        } else {
            // Failed to become leader
            false
        }
    }

    companion object : KLogging() {

        private const val uniqueSuffixLength = 7

        private fun participationPath(path: String) = path.appendToPath("participants")

        private fun leaderPath(electionPath: String) = electionPath.appendToPath("LEADER")

        internal val String.stripUniqueSuffix get() = dropLast(uniqueSuffixLength + 1)

        @JvmStatic
        fun getParticipants(urls: List<String>, electionPath: String): List<Participant> {
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
            val participants = mutableListOf<Participant>()
            connectToEtcd(urls) { client ->
                client.withKvClient { kvClient ->
                    val leader = kvClient.getValue(electionPath)?.asString?.stripUniqueSuffix
                    kvClient.getValues(participationPath(electionPath)).asString
                        .forEach { participants += Participant(it, leader == it) }
                }
            }
            return participants
        }

        @JvmStatic
        fun reportLeader(urls: List<String>,
                         electionPath: String,
                         listener: LeaderListener,
                         executor: Executor): CountDownLatch {
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
            val terminateListener = CountDownLatch(1)
            executor.execute {
                connectToEtcd(urls) { client ->
                    client.withWatchClient { watchClient ->
                        watchClient.watcher(leaderPath(electionPath)) { watchResponse ->
                            watchResponse.events
                                .forEach { event ->
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
                                }
                        }.use {
                            terminateListener.await()
                        }
                    }
                }
            }
            return terminateListener
        }
    }
}