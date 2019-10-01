package org.athenian.election

import io.etcd.jetcd.Client
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import org.athenian.asString
import org.athenian.sleep
import org.athenian.watcher
import org.athenian.withWatchClient
import kotlin.time.ExperimentalTime
import kotlin.time.MonoClock
import kotlin.time.days

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val electionKeyName = "/election1"
    val clock = MonoClock
    var unelectedTime = clock.markNow()

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withWatchClient { watchClient ->
                watchClient.watcher(electionKeyName) { watchResponse ->
                    watchResponse.events
                        .forEach { event ->
                            when (event.eventType) {
                                PUT -> println("${event.keyValue.value.asString} is now the leader [${unelectedTime.elapsedNow()}]")
                                DELETE -> unelectedTime = clock.markNow()
                                UNRECOGNIZED -> println("Error with watch")
                                else -> println("Error with watch")
                            }
                        }
                }.use {
                    // Sleep forever
                    sleep(Long.MAX_VALUE.days)
                }
            }
        }
}