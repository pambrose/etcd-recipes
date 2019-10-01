package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import org.athenian.asPutOption
import org.athenian.putValue
import org.athenian.sleep
import org.athenian.withKvClient
import org.athenian.withLeaseClient
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
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
