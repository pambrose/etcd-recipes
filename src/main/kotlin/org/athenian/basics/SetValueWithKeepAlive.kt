package org.athenian.basics

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import org.athenian.utils.asPutOption
import org.athenian.utils.putValue
import org.athenian.utils.withKvClient
import org.athenian.utils.withLeaseClient
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
