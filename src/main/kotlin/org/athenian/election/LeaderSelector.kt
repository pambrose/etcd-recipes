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

package org.athenian.election

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.concurrent.withLock
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.time.Conversions.Static.timeUnitToDuration
import com.sudothought.common.util.randomId
import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import org.athenian.common.EtcdRecipeException
import org.athenian.common.EtcdRecipeRuntimeException
import org.athenian.jetcd.appendToPath
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.delete
import org.athenian.jetcd.equalTo
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.isKeyPresent
import org.athenian.jetcd.keepAliveWith
import org.athenian.jetcd.putOp
import org.athenian.jetcd.transaction
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withKvClient
import org.athenian.jetcd.withLeaseClient
import org.athenian.jetcd.withWatchClient
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.seconds


// For Java clients
class LeaderSelector(val url: String,
                     val electionPath: String,
                     private val listener: LeaderSelectorListener,
                     val clientId: String,
                     private val userExecutor: ExecutorService?) : Closeable {

    // For Java clients
    constructor(url: String,
                electionPath: String,
                listener: LeaderSelectorListener) : this(url,
                                                         electionPath,
                                                         listener,
                                                         "Client:${randomId(9)}",
                                                         null)

    // For Java clients
    constructor(url: String,
                electionPath: String,
                listener: LeaderSelectorListener,
                clientId: String) : this(url,
                                         electionPath,
                                         listener,
                                         clientId,
                                         null)

    // For Java clients
    constructor(url: String,
                electionPath: String,
                listener: LeaderSelectorListener,
                executorService: ExecutorService) : this(url,
                                                         electionPath,
                                                         listener,
                                                         "Client:${randomId(9)}",
                                                         executorService)

    // For Kotlin clients
    constructor(url: String,
                electionPath: String,
                lambda: (selector: LeaderSelector) -> Unit,
                clientId: String = "Client:${randomId(9)}",
                executorService: ExecutorService? = null) : this(url,
                                                                 electionPath,
                                                                 object : LeaderSelectorListener {
                                                                     override fun takeLeadership(selector: LeaderSelector) {
                                                                         lambda.invoke(selector)
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
    private var startCalled by atomicBoolean(false)
    private var closeCalled by atomicBoolean(false)
    private var electedLeader by atomicBoolean(false)
    private var startCallAllowed by atomicBoolean(true)
    private val leaderPath = leaderPath(electionPath)
    private val exceptionList = mutableListOf<Exception>()

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
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
        startCalled = true
        closeCalled = false
        electedLeader = false
        startCallAllowed = false

        val connectedToEtcd = BooleanMonitor(false)

        executor.submit {
            try {
                Client.builder().endpoints(url).build()
                    .use { client ->
                        client.withLeaseClient { leaseClient ->
                            client.withKvClient { kvClient ->

                                connectedToEtcd.set(true)
                                val leaderSemaphore = Semaphore(1, true)
                                val watchStarted = BooleanMonitor(false)
                                val watchStopped = BooleanMonitor(false)
                                val advertiseComplete = BooleanMonitor(false)

                                executor.submit {
                                    try {
                                        client.withWatchClient { watchClient ->
                                            // Run for leader when leader key is deleted
                                            watchForDeleteEvents(watchClient, watchStarted) {
                                                leaderSemaphore.withLock {
                                                    attemptToBecomeLeader(leaseClient,
                                                                          kvClient)
                                                }
                                            }.use {
                                                terminateWatch.waitUntilTrue()
                                            }
                                            watchStopped.set(true)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        exceptionList += e
                                    }
                                }

                                executor.submit {
                                    try {
                                        advertiseParticipation(leaseClient, kvClient)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
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
            } catch (e: Exception) {
                e.printStackTrace()
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

                if (userExecutor == null)
                    executor.shutdown()
            }
        }
    }


    private fun watchForDeleteEvents(watchClient: Watch,
                                     watchStarted: BooleanMonitor,
                                     block: () -> Unit): Watch.Watcher {
        val watcher = watchClient.watcher(leaderPath) { watchResponse ->
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
                If(equalTo(path, CmpTarget.version(0)))
                Then(putOp(path, clientId, lease.asPutOption))
            }

        if (!txn.isSucceeded) throw EtcdRecipeException("Participation registration failed [$path]")

        // Run keep-alive until closed
        leaseClient.keepAliveWith(lease) {
            terminateKeepAlive.waitUntilTrue()
        }
    }

    // This will not return until election failure or leader surrenders leadership after being elected
    private fun attemptToBecomeLeader(leaseClient: Lease, kvClient: KV): Boolean {
        if (isLeader)
            return false

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$clientId:${randomId(uniqueSuffixLength)}"

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()

        // Do a CAS on the key name. If it is not found, then set it
        val txn =
            kvClient.transaction {
                If(equalTo(leaderPath, CmpTarget.version(0)))
                Then(putOp(leaderPath, uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (!isLeader && txn.isSucceeded && kvClient.getStringValue(leaderPath) == uniqueToken) {
            // This will exit when leadership is relinquished
            leaseClient.keepAliveWith(lease) {
                // Selected as leader
                electedLeader = true
                listener.takeLeadership(this)
            }

            // Leadership was relinquished
            // Do this after leadership is complete so the thread does not terminate
            markLeadershipComplete()
            startCallAllowed = true
            electedLeader = false
            true
        } else {
            // Failed to become leader
            false
        }
    }

    companion object Static {

        private const val uniqueSuffixLength = 9

        private fun participationPath(path: String) = path.appendToPath("participants")

        fun translateLeaderId(id: String) = id.dropLast(uniqueSuffixLength + 1)

        fun leaderPath(electionPath: String) = electionPath.appendToPath("LEADER")

        fun reset(url: String, electionPath: String) {
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        kvClient.getChildrenKeys(electionPath).forEach { kvClient.delete(it) }
                    }
                }
        }

        fun getParticipants(url: String, electionPath: String): List<Participant> {
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
            val retval = mutableListOf<Participant>()
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        val leader = kvClient.getStringValue(electionPath)?.dropLast(uniqueSuffixLength + 1)
                        kvClient.getChildrenStringValues(participationPath(electionPath))
                            .forEach { retval += Participant(it, leader == it) }
                    }
                }
            return retval
        }
    }
}