package org.athenian.basics

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import io.etcd.jetcd.options.WatchOption
import org.athenian.jetcd.asByteSequence
import org.athenian.jetcd.asString
import org.athenian.jetcd.countChildren
import org.athenian.jetcd.delete
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.putValue
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withKvClient
import org.athenian.jetcd.withWatchClient
import kotlin.time.seconds

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
