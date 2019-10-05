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

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.jetcd.watch.WatchEvent.EventType.UNRECOGNIZED
import org.athenian.election.LeaderSelector.Static.leaderPath
import org.athenian.election.LeaderSelector.Static.translateLeaderId
import org.athenian.jetcd.asString
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withWatchClient
import kotlin.time.MonoClock
import kotlin.time.days

fun main() {
    val url = "http://localhost:2379"
    val electionName = "/election/threaded"
    val clock = MonoClock
    var unelectedTime = clock.markNow()

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withWatchClient { watchClient ->
                watchClient.watcher(leaderPath(electionName)) { watchResponse ->
                    watchResponse.events
                        .forEach { event ->
                            when (event.eventType) {
                                PUT -> println("${translateLeaderId(event.keyValue.value.asString)} is now the leader [${unelectedTime.elapsedNow()}]")
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