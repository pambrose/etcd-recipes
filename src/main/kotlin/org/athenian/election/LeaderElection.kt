package org.athenian.election

import io.etcd.jetcd.*
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import org.athenian.*
import java.io.Closeable
import java.lang.Math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
class LeaderElection(val url: String,
                     val electionKeyName: String = defaultElectionKeyName,
                     val id: String = "Unassigned:${abs(Random.nextInt())}") : Closeable {
    private val executor = Executors.newFixedThreadPool(2)
    private val startCountdown = CountDownLatch(1)
    private val initCountDown = CountDownLatch(1)
    private val watchCountDown = CountDownLatch(1)

    fun start(actions: ElectionActions): LeaderElection {
        executor.submit {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withLeaseClient { leaseClient ->
                        client.withWatchClient { watchClient ->
                            client.withKvClient { kvclient ->
                                val countdown = CountDownLatch(1)

                                initCountDown.countDown()

                                executor.submit {
                                    watchForLeadershipOpening(watchClient) {
                                        // Run for leader when leader key is deleted
                                        attemptToBecomeLeader(actions, leaseClient, kvclient)
                                    }.use {
                                        watchCountDown.await()
                                    }
                                }

                                // Give the watcher a chance to start
                                sleep(2.seconds)

                                // Clients should run for leader in case they are the first to run
                                attemptToBecomeLeader(actions, leaseClient, kvclient)

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

    fun await(duration: Duration = 0.milliseconds): Boolean =
        startCountdown.await(duration.toLongMilliseconds(), TimeUnit.MILLISECONDS)

    override fun close() {
        watchCountDown.countDown()
        sleep(1.seconds)
        startCountdown.countDown()
        sleep(1.seconds)
        executor.shutdown()
    }

    private fun watchForLeadershipOpening(watchClient: Watch, action: () -> Unit): Watch.Watcher {
        val watchOptions = WatchOption.newBuilder().withRevision(0).build()
        return watchClient.watch(electionKeyName.asByteSequence, watchOptions) { resp ->
            // Create a watch to act on DELETE events
            resp.events
                .forEach { event ->
                    if (event.eventType == WatchEvent.EventType.DELETE) {
                        //println("$clientId executing action")
                        action.invoke()
                    }
                }
        }
    }

    // This will not return until election failure or leader surrenders leadership
    private fun attemptToBecomeLeader(actions: ElectionActions, leaseClient: Lease, kvclient: KV) {
        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.grant(2).get()
        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$id:${abs(Random.nextInt())}"

        // Do a CAS on on the key name. If it is not found, then set it
        kvclient.transaction {
            If(equals(electionKeyName, CmpTarget.version(0)))
            Then(put(electionKeyName, uniqueToken, lease.asPutOption))
        }

        // Check to see if unique value was successfully set in the CAS step
        if (kvclient.getValue(electionKeyName) == uniqueToken) {
            leaseClient.keepAlive(lease.id,
                                  Observers.observer(
                                      { next -> /*println("KeepAlive next resp: $next")*/ },
                                      { err -> /*println("KeepAlive err resp: $err")*/ })
            ).use {
                actions.onElected.invoke()
            }
            actions.onTermComplete.invoke()
        } else {
            actions.onFailedElection.invoke()
        }
    }

    companion object {
        val defaultElectionKeyName = "/election/leader"

        fun resetKeys(url: String, electionKeyName: String = defaultElectionKeyName) {
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient ->
                        kvclient.delete(electionKeyName)
                    }
                }
        }
    }
}

typealias ElectionAction = () -> Unit

class ElectionActions(val onElected: ElectionAction = {},
                      val onTermComplete: ElectionAction = {},
                      val onFailedElection: ElectionAction = {},
                      val onInitComplete: ElectionAction = {})

