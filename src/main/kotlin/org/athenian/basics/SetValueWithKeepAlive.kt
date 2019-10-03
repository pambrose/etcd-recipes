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

package org.athenian.basics

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.putValue
import org.athenian.jetcd.withKvClient
import org.athenian.jetcd.withLeaseClient
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val keyname = "/foo"
    val keyval = "foobar"

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withLeaseClient { leaseClient ->
                client.withKvClient { kvClient ->
                    val lease = leaseClient.grant(1).get()
                    println("Assigning $keyname = $keyval")
                    kvClient.putValue(keyname, keyval, lease.asPutOption)
                    leaseClient.keepAlive(lease.id,
                                          Observers.observer({ next ->
                                                                 println("KeepAlive next resp: $next")
                                                             },
                                                             { err ->
                                                                 println("KeepAlive err resp: $err")
                                                             })
                    ).use {
                        println("Starting sleep")
                        sleep(10.seconds)
                        println("Finished sleep")
                    }
                    println("Keep-alive is now terminated")
                    sleep(5.seconds)
                }
            }
        }
}
