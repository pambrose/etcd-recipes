package org.athenian.election

import com.sudothought.common.concurrent.isFinished
import com.sudothought.common.delegate.AtomicDelegates
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
import org.athenian.jetcd.append
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.delete
import org.athenian.jetcd.equals
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.keepAliveUntil
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

    private class LeaderSelectorContext {
        val watchForDeleteLatch = CountDownLatch(1)
        val leadershipCompleteLatch = CountDownLatch(1)
        var startCalled by atomicBoolean(false)
        var closeCalled by atomicBoolean(false)
        var electedLeader by atomicBoolean(false)
        var startCallAllowed by atomicBoolean(true)
    }

    private var selectorContext by AtomicDelegates.nonNullableReference(LeaderSelectorContext())
    private val context get() = selectorContext
    private val executor = userExecutor ?: Executors.newFixedThreadPool(3)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    val isStartCalled get() = context.startCalled

    val isCloseCalled get() = context.closeCalled

    val isLeader get() = context.electedLeader

    val isFinished get() = context.leadershipCompleteLatch.isFinished

    fun start(): LeaderSelector {

        check(context.startCallAllowed) { "Previous call to start() not complete" }
        checkCloseNotCalled()

        // Reinitialize the context each time through
        selectorContext =
            LeaderSelectorContext().apply {
                startCalled = true
                startCallAllowed = false
            }

        val clientInitLatch = CountDownLatch(1)

        executor.submit {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withWatchClient { watchClient ->
                            client.withKvClient { kvClient ->

                                clientInitLatch.countDown()

                                executor.submit {
                                    // Run for leader when leader key is deleted
                                    watchForDeleteEvents(watchClient) {
                                        attemptToBecomeLeader(leaseClient, kvClient)
                                    }.use {
                                        context.watchForDeleteLatch.await()
                                    }
                                }

                                executor.submit { advertiseParticipation(leaseClient, kvClient) }

                                // Give the watcher a chance to start
                                sleep(1.seconds)

                                // Clients should run for leader in case they are the first to run
                                attemptToBecomeLeader(leaseClient, kvClient)

                                context.leadershipCompleteLatch.await()
                            }
                        }
                    }
                }
        }

        // Wait for connection to etcd
        clientInitLatch.await()

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
        return context.leadershipCompleteLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        checkStartCalled()
        checkCloseNotCalled()

        context.apply {
            closeCalled = true
            watchForDeleteLatch.countDown()
            leadershipCompleteLatch.countDown()
        }

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
        val participantPath = participationPath(electionPath).append(clientId)

        val txn =
            kvClient.transaction {
                If(equals(participantPath, CmpTarget.version(0)))
                Then(putOp(participantPath, clientId, lease.asPutOption))
            }

        check(txn.isSucceeded) { "Participation registration failed" }

        // Run keep-alive until closed
        leaseClient.keepAliveUntil(lease) { context.leadershipCompleteLatch.await() }
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
            leaseClient.keepAliveUntil(lease) {
                // Selected as leader
                context.electedLeader = true
                listener.takeLeadership(this)
            }

            // Leadership was relinquished
            context.apply {
                // Do this after leadership is complete so the thread does not terminate
                watchForDeleteLatch.countDown()
                leadershipCompleteLatch.countDown()
                startCallAllowed = true
                electedLeader = false
            }
            true
        } else {
            // Failed to become leader
            false
        }
    }

    companion object Static {

        private val uniqueSuffixLength = 9

        private fun participationPath(path: String) = path.append("participants")

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
                        kvClient.getChildrenStringValues(participationPath(electionPath)).forEach {
                            retval += Participant(it, leader == it)
                        }
                    }
                }
            return retval
        }
    }
}