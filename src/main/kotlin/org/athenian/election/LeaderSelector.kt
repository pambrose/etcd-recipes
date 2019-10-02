package org.athenian.election

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Observers
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import org.athenian.utils.append
import org.athenian.utils.asPutOption
import org.athenian.utils.delete
import org.athenian.utils.equals
import org.athenian.utils.getChildrenKeys
import org.athenian.utils.getChildrenStringValues
import org.athenian.utils.getStringValue
import org.athenian.utils.putOp
import org.athenian.utils.randomId
import org.athenian.utils.sleep
import org.athenian.utils.timeUnitToDuration
import org.athenian.utils.transaction
import org.athenian.utils.watcher
import org.athenian.utils.withKvClient
import org.athenian.utils.withLeaseClient
import org.athenian.utils.withWatchClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days
import kotlin.time.seconds


@ExperimentalTime
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
        val startCalled = AtomicBoolean(false)
        val closeCalled = AtomicBoolean(false)
        val watchForDeleteLatch = CountDownLatch(1)
        val leadershipCompleteLatch = CountDownLatch(1)
        val electedLeader = AtomicBoolean(false)
        val startCallAllowed = AtomicBoolean(true)
    }

    private var selectorContext = AtomicReference(LeaderSelectorContext())
    private val context get() = selectorContext.get()
    private val executor = userExecutor ?: Executors.newFixedThreadPool(3)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    val isStartCalled get() = context.startCalled.get()

    val isCloseCalled get() = context.closeCalled.get()

    val isLeader get() = context.electedLeader.get()

    val isFinished get() = context.leadershipCompleteLatch.count == 0L

    fun start(): LeaderSelector {

        check(context.startCallAllowed.get()) { "Previous call to start() not complete" }
        checkCloseNotCalled()

        // Reinitialize the context each time through
        selectorContext.set(
            LeaderSelectorContext().apply {
                startCalled.set(true)
                startCallAllowed.set(false)
            }
        )

        val clientInitLatch = CountDownLatch(1)

        executor.submit {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withWatchClient { watchClient ->
                            client.withKvClient { kvClient ->

                                clientInitLatch.countDown()

                                executor.submit {
                                    watchForDeleteEvents(watchClient) {
                                        // Run for leader when leader key is deleted
                                        attemptToBecomeLeader(leaseClient, kvClient)
                                    }.use {
                                        context.watchForDeleteLatch.await()
                                    }
                                }

                                executor.submit {
                                    advertiseParticipation(leaseClient, kvClient)
                                }

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
            closeCalled.set(true)
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
            // Create a watch to act on DELETE events
            watchResponse.events
                .forEach { event ->
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

        leaseClient.keepAlive(lease.id,
                              Observers.observer(
                                  { /*println("KeepAlive next resp: $next")*/ },
                                  { /*println("KeepAlive err resp: $err")*/ })
        ).use {
            // Run keep-alive until closed
            context.leadershipCompleteLatch.await()
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
                If(equals(electionPath, CmpTarget.version(0)))
                Then(putOp(electionPath, uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (!isLeader && txn.isSucceeded && kvClient.getStringValue(electionPath) == uniqueToken) {
            leaseClient.keepAlive(lease.id,
                                  Observers.observer(
                                      { /*println("KeepAlive next resp: $next")*/ },
                                      { /*println("KeepAlive err resp: $err")*/ })
            ).use {
                // Was selected as leader
                context.electedLeader.set(true)
                listener.takeLeadership(this)
            }

            // Leadership was relinquished
            context.apply {
                // Do this after leadership is complete so the thread does not terminate
                watchForDeleteLatch.countDown()
                leadershipCompleteLatch.countDown()
                startCallAllowed.set(true)
                electedLeader.set(false)
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