package org.athenian.barrier

import io.etcd.jetcd.Client
import org.athenian.jetcd.countChildren
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.withKvClient

fun main() {
    val url = "http://localhost:2379"
    val keyname = "/barriers/barrier2"

    // DistributedBarrierWithCount.reset(url, keyname)

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withKvClient { kvClient ->
                kvClient.apply {
                    println(getChildrenKeys(keyname))
                    println(getChildrenStringValues(keyname))
                    println(countChildren(keyname))
                }
            }
        }
}