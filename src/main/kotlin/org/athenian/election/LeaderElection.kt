package org.athenian.election

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Observers
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import org.athenian.utils.asPutOption
import org.athenian.utils.delete
import org.athenian.utils.equals
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days
import kotlin.time.seconds

@ExperimentalTime
interface LeaderSelectorListener {
    @Throws(Exception::class)
    fun takeLeadership(election: LeaderElection)
}

class LeaderElectionContext {
    val watchForDeleteLatch = CountDownLatch(1)
    val leadershipCompleteLatch = CountDownLatch(1)
    val electedLeader = AtomicBoolean(false)
    val startCallStarted = AtomicBoolean(false)
    val startCallCompleted = AtomicBoolean(false)
}

@ExperimentalTime
class LeaderElection(val url: String,
                     val electionPath: String,
                     val actions: ElectionActions,
                     val id: String) : Closeable {

    constructor(url: String, electionPath: String, actions: ElectionActions) : this(url,
                                                                                    electionPath,
                                                                                    actions,
                                                                                    "Client:${randomId(
                                                                                        9)}")

    private val executor = Executors.newFixedThreadPool(2)
    private var context = LeaderElectionContext()

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    fun start(): LeaderElection {
        if (context.startCallStarted.get() && !context.startCallCompleted.get())
            throw IllegalStateException("Previous call to start() not complete")

        context = LeaderElectionContext()
        context.startCallStarted.set(true)

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

                                // Give the watcher a chance to start
                                sleep(2.seconds)

                                // Clients should run for leader in case they are the first to run
                                attemptToBecomeLeader(leaseClient, kvClient)

                                context.leadershipCompleteLatch.await()
                            }
                        }
                    }
                }
        }

        clientInitLatch.await()

        actions.onInitComplete.invoke(this)

        context.startCallCompleted.set(true)

        return this
    }

    fun await(): Boolean = await(Long.MAX_VALUE.days)

    fun await(timeout: Long, timeUnit: TimeUnit): Boolean = await(timeUnitToDuration(timeout,
                                                                                     timeUnit))

    fun await(timeout: Duration): Boolean =
        context.leadershipCompleteLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)

    override fun close() {
        context.watchForDeleteLatch.countDown()
        context.leadershipCompleteLatch.countDown()

        executor.shutdown()
    }

    private fun watchForDeleteEvents(watchClient: Watch, action: () -> Unit): Watch.Watcher =
        watchClient.watcher(electionPath) { watchResponse ->
            // Create a watch to act on DELETE events
            watchResponse.events
                .forEach { event ->
                    if (event.eventType == DELETE) {
                        //println("$clientId executing action")
                        action.invoke()
                    }
                }
        }

    // This will not return until election failure or leader surrenders leadership
    private fun attemptToBecomeLeader(leaseClient: Lease, kvClient: KV): Boolean {
        if (context.electedLeader.get())
            return false

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$id:${randomId(9)}"

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()

        // Do a CAS on the key name. If it is not found, then set it
        val txn =
            kvClient.transaction {
                If(equals(electionPath, CmpTarget.version(0)))
                Then(putOp(electionPath, uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (!context.electedLeader.get() && txn.isSucceeded && kvClient.getStringValue(electionPath) == uniqueToken) {
            leaseClient.keepAlive(lease.id,
                                  Observers.observer(
                                      { /*println("KeepAlive next resp: $next")*/ },
                                      { /*println("KeepAlive err resp: $err")*/ })
            ).use {
                // Was selected as leader
                context.electedLeader.set(true)
                actions.onElected.invoke(this)
            }

            // Leadership was relinquished

            actions.onTermComplete.invoke(this)

            // Do this after leadership is complete so the thread does not terminate
            context.watchForDeleteLatch.countDown()
            context.leadershipCompleteLatch.countDown()
            true
        } else {
            // Failed to become leader
            actions.onFailedElection.invoke(this)
            false
        }
    }

    companion object {
        fun reset(url: String, electionPath: String) {
            require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { it.delete(electionPath) }
                }
        }
    }
}

@ExperimentalTime
typealias ElectionAction = (election: LeaderElection) -> Unit

@ExperimentalTime
class ElectionActions(val onElected: ElectionAction = {},
                      val onTermComplete: ElectionAction = {},
                      val onFailedElection: ElectionAction = {},
                      val onInitComplete: ElectionAction = {})

