package org.athenian.election

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import org.athenian.jetcd.asString
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withWatchClient
import kotlin.time.MonoClock
import kotlin.time.days

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