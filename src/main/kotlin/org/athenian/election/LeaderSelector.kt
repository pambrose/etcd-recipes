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
import org.athenian.jetcd.appendToPath
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.delete
import org.athenian.jetcd.equals
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.keepAliveWith
import org.athenian.jetcd.putOp
import org.athenian.jetcd.transaction
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withKvClient
import org.athenian.jetcd.withLeaseClient
import org.athenian.jetcd.withWatchClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    private val executor = userExecutor ?: Executors.newFixedThreadPool(3)
    private val terminateWatchForDelete = BooleanMonitor(false)
    private val leadershipComplete = BooleanMonitor(false)
    private var startCalled by atomicBoolean(false)
    private var closeCalled by atomicBoolean(false)
    private var electedLeader by atomicBoolean(false)
    private var startCallAllowed by atomicBoolean(true)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    val isStartCalled get() = startCalled

    val isCloseCalled get() = closeCalled

    val isLeader get() = electedLeader

    val isFinished get() = leadershipComplete.get()

    fun start(): LeaderSelector {

        check(startCallAllowed) { "Previous call to start() not complete" }
        checkCloseNotCalled()

        terminateWatchForDelete.set(false)
        leadershipComplete.set(false)
        electedLeader = false
        startCalled = true
        startCallAllowed = false

        val connectedToEtcd = CountDownLatch(1)

        executor.submit {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withWatchClient { watchClient ->
                            client.withKvClient { kvClient ->

                                connectedToEtcd.countDown()

                                executor.submit {
                                    // Run for leader when leader key is deleted
                                    watchForDeleteEvents(watchClient) {
                                        attemptToBecomeLeader(leaseClient, kvClient)
                                    }.use {
                                        terminateWatchForDelete.waitUntilTrue()
                                    }
                                }

                                executor.submit { advertiseParticipation(leaseClient, kvClient) }

                                // Give the watcher a chance to start
                                sleep(1.seconds)

                                // Clients should run for leader in case they are the first to run
                                attemptToBecomeLeader(leaseClient, kvClient)

                                leadershipComplete.waitUntilTrue()
                            }
                        }
                    }
                }
        }

        connectedToEtcd.await()
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
        return leadershipComplete.waitUntilTrue(timeout)
    }

    private fun closeLatches() {
        terminateWatchForDelete.set(true)
        leadershipComplete.set(true)
    }

    override fun close() {
        checkStartCalled()
        checkCloseNotCalled()

        closeLatches()
        closeCalled = true

        if (userExecutor == null)
            executor.shutdown()
    }

    private fun checkStartCalled() = check(isStartCalled) { "start() not called" }

    private fun checkCloseNotCalled() = check(!isCloseCalled) { "close() already closed" }

    private fun watchForDeleteEvents(watchClient: Watch, action: () -> Unit): Watch.Watcher =
        watchClient.watcher(electionPath) { watchResponse ->
            watchResponse.events.forEach { event ->
                if (event.eventType == DELETE) action.invoke()
            }
        }

    private fun advertiseParticipation(leaseClient: Lease, kvClient: KV) {
        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()
        val participantPath = participationPath(electionPath).appendToPath(clientId)

        val txn =
            kvClient.transaction {
                If(equals(participantPath, CmpTarget.version(0)))
                Then(putOp(participantPath, clientId, lease.asPutOption))
            }

        check(txn.isSucceeded) { "Participation registration failed" }

        // Run keep-alive until closed
        leaseClient.keepAliveWith(lease) { leadershipComplete.waitUntilTrue() }
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
                If(equals(electionPath, CmpTarget.version(0)))
                Then(putOp(electionPath, uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (!isLeader && txn.isSucceeded && kvClient.getStringValue(electionPath) == uniqueToken) {
            // This will exit when leadership is relinquished
            leaseClient.keepAliveWith(lease) {
                // Selected as leader
                electedLeader = true
                listener.takeLeadership(this)
            }

            // Leadership was relinquished
            // Do this after leadership is complete so the thread does not terminate
            closeLatches()
            startCallAllowed = true
            electedLeader = false
            true
        } else {
            // Failed to become leader
            false
        }
    }

    companion object Static {

        private val uniqueSuffixLength = 9

        private fun participationPath(path: String) = path.appendToPath("participants")

        fun reset(url: String, electionPath: String) {
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        kvClient.getChildrenKeys(electionPath).forEach {
                            kvClient.delete(it)
                        }
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