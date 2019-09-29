package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import org.athenian.asByteSequence
import org.athenian.asString
import org.athenian.countChildren
import org.athenian.delete
import org.athenian.getChildrenKeys
import org.athenian.getChildrenStringValues
import org.athenian.putValue
import org.athenian.sleep
import org.athenian.watcher
import org.athenian.withKvClient
import org.athenian.withWatchClient
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/keyrangetest"


    Client.builder().endpoints(url).build()
        .use { client ->
            client.withWatchClient { watchClient ->
                client.withKvClient { kvClient ->
                    kvClient.apply {

                        val option = WatchOption.newBuilder().withPrefix("/".asByteSequence).build()
                        val watch =
                            watchClient.watcher(keyname, option) { watchResponse ->
                                watchResponse.events
                                    .forEach { watchEvent ->
                                        val key = watchEvent.keyValue.key.asString
                                        println("Got event ${watchEvent.eventType} for $key")
                                    }

                            }.use {

                                // Create empty root
                                putValue(keyname, "root")

                                println("After creation:")
                                println(getChildrenKeys(keyname))
                                println(getChildrenStringValues(keyname))
                                println(countChildren(keyname))

                                sleep(5.seconds)

                                // Add children
                                putValue("$keyname/election/a", "a")
                                putValue("$keyname/election/b", "bb")
                                putValue("$keyname/waiting/c", "ccc")
                                putValue("$keyname/waiting/d", "dddd")

                                println("\nAfter addition:")
                                println(getChildrenKeys(keyname))
                                println(getChildrenStringValues(keyname))
                                println(countChildren(keyname))

                                println("\nElections only:")
                                println(getChildrenKeys("$keyname/election"))
                                println(getChildrenStringValues("$keyname/election"))
                                println(countChildren("$keyname/election"))

                                println("\nWaitings only:")
                                println(getChildrenKeys("$keyname/waiting"))
                                println(getChildrenStringValues("$keyname/waiting"))
                                println(countChildren("$keyname/waiting"))

                                sleep(5.seconds)

                                // Delete root
                                delete(keyname)

                                // Delete children
                                getChildrenKeys(keyname).forEach {
                                    println("Deleting key: $it")
                                    delete(it)
                                }


                                println("\nAfter removal:")
                                println(getChildrenKeys(keyname))
                                println(getChildrenStringValues(keyname))
                                println(countChildren(keyname))

                                sleep(5.seconds)

                            }
                    }
                }
            }
        }
}