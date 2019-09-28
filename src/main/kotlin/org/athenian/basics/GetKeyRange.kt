package org.athenian.basics

import io.etcd.jetcd.Client
import org.athenian.countChildren
import org.athenian.delete
import org.athenian.getChildrenStringValues
import org.athenian.putValue
import org.athenian.withKvClient
import kotlin.time.ExperimentalTime

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/keyrangetest"


    Client.builder().endpoints(url).build()
        .use { client ->
            client.withKvClient { kvClient ->

                kvClient.apply {
                    // Create empty root
                    putValue(keyname, "root")

                    println("After creation:")
                    println(getChildrenStringValues(keyname))
                    println(countChildren(keyname))

                    // Add children
                    putValue("$keyname/a", "a")
                    putValue("$keyname/b", "bb")
                    putValue("$keyname/c", "ccc")
                    putValue("$keyname/d", "dddd")

                    println("\nAfter addition:")
                    println(getChildrenStringValues(keyname))
                    println(countChildren(keyname))

                    // Remove children
                    delete("$keyname/a")
                    delete("$keyname/b")
                    delete("$keyname/c")
                    delete("$keyname/d")

                    println("\nAfter removal:")
                    println(getChildrenStringValues("$keyname/"))
                    println(countChildren(keyname))
                }
            }
        }
}
