package org.athenian.basics

import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import org.athenian.utils.asByteSequence
import org.athenian.utils.asString
import org.athenian.utils.countChildren
import org.athenian.utils.delete
import org.athenian.utils.getChildrenKeys
import org.athenian.utils.getChildrenStringValues
import org.athenian.utils.putValue
import org.athenian.utils.sleep
import org.athenian.utils.watcher
import org.athenian.utils.withKvClient
import org.athenian.utils.withWatchClient
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
