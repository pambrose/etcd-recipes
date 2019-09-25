package org.athenian.election

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import org.athenian.asByteSequence
import org.athenian.asString
import java.util.concurrent.CountDownLatch
import kotlin.time.ExperimentalTime
import kotlin.time.MonoClock

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val electionKeyName = LeaderElection.defaultElectionKeyName
    val clock = MonoClock
    var unelectedTime = clock.markNow()

    Client.builder().endpoints(url).build()
        .use { client ->
            client.watchClient
                .use { watchClient ->
                    val watchOptions = WatchOption.newBuilder().withRevision(0).build()
                    watchClient.watch(electionKeyName.asByteSequence, watchOptions) { resp ->
                        resp.events
                            .forEach { event ->
                                when (event.eventType) {
                                    WatchEvent.EventType.PUT -> {
                                        println("${event.keyValue.value.asString} is now the leader [${unelectedTime.elapsedNow()}]")
                                    }
                                    WatchEvent.EventType.DELETE -> {
                                        unelectedTime = clock.markNow()
                                    }
                                    WatchEvent.EventType.UNRECOGNIZED -> {
                                        println("Error with watch")
                                    }
                                    else -> {
                                        println("Error with watch")
                                    }
                                }
                            }
                    }
                    // Sleep forever
                    val countdown = CountDownLatch(1)
                    countdown.await()
                }
        }
}