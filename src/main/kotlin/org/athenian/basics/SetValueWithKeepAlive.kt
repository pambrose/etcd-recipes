package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import org.athenian.asPutOption
import org.athenian.putValue
import org.athenian.withKvClient
import org.athenian.withLeaseClient
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/foo"
    val keyval = "foobar"

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withLeaseClient { leaseClient ->
                client.withKvClient { kvclient ->
                    println("Assigning $keyname = $keyval")
                    val lease = leaseClient.grant(1).get()
                    kvclient.putValue(keyname, keyval, lease.asPutOption)
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
