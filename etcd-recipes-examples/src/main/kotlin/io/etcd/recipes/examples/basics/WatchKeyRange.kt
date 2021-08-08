/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.examples.basics

import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.*
import kotlin.time.Duration

fun main() {
  val urls = listOf("http://localhost:2379")
  val path = "/watchkeyrange"

  connectToEtcd(urls) { client ->
    client.apply {
      val watchOption = watchOption { isPrefix(true) }
      withWatcher(path,
        watchOption,
        { watchResponse ->
          for (event in watchResponse.events)
            println("${event.eventType} for ${event.keyValue.asString}")
        }) {
        // Create empty root
        putValue(path, "root")

        println("After creation:")
        println(getChildren(path))
        println(getChildCount(path))

        sleep(Duration.seconds(5))

        // Add children
        putValue("$path/election/a", "a")
        putValue("$path/election/b", "bb")
        putValue("$path/waiting/c", "ccc")
        putValue("$path/waiting/d", "dddd")

        println("\nAfter putValues:")
        println(getChildren(path).asString)
        println(getChildCount(path))

        println("\nElection only:")
        println(getChildren("$path/election").asString)
        println(getChildCount("$path/election"))

        println("\nWaiting only:")
        println(getChildren("$path/waiting").asString)
        println(getChildCount("$path/waiting"))

        sleep(Duration.seconds(5))

        // Delete root
        deleteKey(path)

        // Delete children
        getChildrenKeys(path).forEach {
          println("Deleting key: $it")
          deleteKey(it)
        }

        println("\nAfter removal:")
        println(getChildren(path).asString)
        println(getChildCount(path))

        sleep(Duration.seconds(5))
      }
    }
  }
}