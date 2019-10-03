package org.athenian.barrier

import io.etcd.jetcd.Client
import org.athenian.utils.countChildren
import org.athenian.utils.getChildrenKeys
import org.athenian.utils.getChildrenStringValues
import org.athenian.utils.withKvClient

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