/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.examples.basics

import com.sudothought.common.util.sleep
import io.etcd.recipes.common.asPrefixWatchOption
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getChildren
import io.etcd.recipes.common.getChildrenCount
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.watcher
import io.etcd.recipes.common.withWatchClient
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/watchkeyrange"

    etcdExec(urls) { client, kvClient ->
        kvClient.apply {

            client.withWatchClient { watchClient ->
                watchClient.watcher(path, path.asPrefixWatchOption) { watchResponse ->
                    watchResponse.events
                        .forEach { watchEvent ->
                            println("${watchEvent.eventType} for ${watchEvent.keyValue.asString}")
                        }
                }.use {
                    // Create empty root
                    putValue(path, "root")

                    println("After creation:")
                    println(getChildren(path))
                    println(getChildrenCount(path))

                    sleep(5.seconds)

                    // Add children
                    putValue("$path/election/a", "a")
                    putValue("$path/election/b", "bb")
                    putValue("$path/waiting/c", "ccc")
                    putValue("$path/waiting/d", "dddd")

                    println("\nAfter putValues:")
                    println(getChildren(path).asString)
                    println(getChildrenCount(path))

                    println("\nElection only:")
                    println(getChildren("$path/election").asString)
                    println(getChildrenCount("$path/election"))

                    println("\nWaiting only:")
                    println(getChildren("$path/waiting").asString)
                    println(getChildrenCount("$path/waiting"))

                    sleep(5.seconds)

                    // Delete root
                    delete(path)

                    // Delete children
                    getChildrenKeys(path).forEach {
                        println("Deleting key: $it")
                        delete(it)
                    }

                    println("\nAfter removal:")
                    println(getChildren(path).asString)
                    println(getChildrenCount(path))

                    sleep(5.seconds)
                }
            }
        }
    }
}
