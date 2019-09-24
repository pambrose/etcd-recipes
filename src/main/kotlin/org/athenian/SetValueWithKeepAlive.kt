package org.athenian

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/election"
    val keyval = "foobar"

    Client.builder().endpoints(url).build()
        .use { client ->
            client.leaseClient
                .use { leaseClient ->
                    client.kvClient
                        .use { kvclient ->
                            println("Assigning $keyname = $keyval")
                            val lease = leaseClient.grant(1).get()
                            kvclient.put(keyname, keyval, lease.asPutOption)
                            leaseClient.keepAlive(lease.id,
                                                  Observers.observer({ next ->
                                                                         println("KeepAlive next resp: $next")
                                                                     },
                                                                     { err ->
                                                                         println("KeepAlive err resp: $err")
                                                                     })

                            ).use {
                                println("Starting sleep")
                                Thread.sleep(20_000)
                                println("Finished sleep")

                            }
                        }
                }
        }
}
