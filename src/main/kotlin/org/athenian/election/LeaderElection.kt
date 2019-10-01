package org.athenian.election

import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Observers
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import org.athenian.asPutOption
import org.athenian.delete
import org.athenian.equals
import org.athenian.getStringValue
import org.athenian.putOp
import org.athenian.randomId
import org.athenian.sleep
import org.athenian.timeUnitToDuration
import org.athenian.transaction
import org.athenian.watcher
import org.athenian.withKvClient
import org.athenian.withLeaseClient
import org.athenian.withWatchClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days
import kotlin.time.seconds

@ExperimentalTime
class LeaderElection(val url: String, val electionPath: String, val id: String) : Closeable {

    constructor(url: String, electionPath: String) : this(url, electionPath, "Client:${randomId(9)}")

    private val executor = lazy { Executors.newFixedThreadPool(2) }
    private val startCountdown = CountDownLatch(1)
    private val initCountDown = CountDownLatch(1)
    private val watchCountDown = CountDownLatch(1)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(electionPath.isNotEmpty()) { "Election path cannot be empty" }
    }

    fun start(actions: ElectionActions): LeaderElection {
        executor.value.submit {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withWatchClient { watchClient ->
                            client.withKvClient { kvClient ->
                                val countdown = CountDownLatch(1)

                                initCountDown.countDown()

                                executor.value.submit {
                                    watchForLeadershipOpening(watchClient) {
                                        // Run for leader when leader key is deleted
                                        attemptToBecomeLeader(actions, leaseClient, kvClient)
                                    }.use {
                                        watchCountDown.await()
                                    }
                                }

                                // Give the watcher a chance to start
                                sleep(2.seconds)

                                // Clients should run for leader in case they are the first to run
                                attemptToBecomeLeader(actions, leaseClient, kvClient)

                                countdown.await()
                            }
                        }
                    }
                }
        }

        initCountDown.await()
        actions.onInitComplete.invoke()

        return this
    }

    fun await(): Boolean = await(Long.MAX_VALUE.days)

    fun await(timeout: Long, timeUnit: TimeUnit): Boolean = await(timeUnitToDuration(timeout, timeUnit))

    fun await(timeout: Duration): Boolean =
        startCountdown.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)

    override fun close() {
        watchCountDown.countDown()
        sleep(1.seconds)

        startCountdown.countDown()
        sleep(1.seconds)

        if (executor.isInitialized())
            executor.value.shutdown()
    }

    private fun watchForLeadershipOpening(watchClient: Watch, action: () -> Unit): Watch.Watcher =
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
    private fun attemptToBecomeLeader(actions: ElectionActions, leaseClient: Lease, kvClient: KV): Boolean {
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
        return if (txn.isSucceeded && kvClient.getStringValue(electionPath) == uniqueToken) {
            leaseClient.keepAlive(lease.id,
                                  Observers.observer(
                                      { /*println("KeepAlive next resp: $next")*/ },
                                      { /*println("KeepAlive err resp: $err")*/ })
            ).use {
                actions.onElected.invoke()
            }
            actions.onTermComplete.invoke()
            true
        } else {
            actions.onFailedElection.invoke()
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

typealias ElectionAction = () -> Unit

class ElectionActions(val onElected: ElectionAction = {},
                      val onTermComplete: ElectionAction = {},
                      val onFailedElection: ElectionAction = {},
                      val onInitComplete: ElectionAction = {})

