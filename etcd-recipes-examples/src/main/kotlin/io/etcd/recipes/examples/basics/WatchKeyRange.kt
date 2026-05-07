/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import com.pambrose.common.util.sleep
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getChildren
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.withWatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds

fun main() {
  val logger = KotlinLogging.logger {}
  val urls = listOf("http://localhost:2379")
  val path = "/watchkeyrange"

  connectToEtcd(urls) { client ->
    client.apply {
      val watchOption = watchOption { isPrefix(true) }
      withWatcher(
        path,
        watchOption,
        { watchResponse ->
          for (event in watchResponse.events) {
            logger.info { "${event.eventType} for ${event.keyValue.asString}" }
          }
        },
      ) {
        // Create empty root
        putValue(path, "root")

        logger.info { "After creation:" }
        logger.info { getChildren(path) }
        logger.info { getChildCount(path) }

        sleep(5.seconds)

        // Add children
        putValue("$path/election/a", "a")
        putValue("$path/election/b", "bb")
        putValue("$path/waiting/c", "ccc")
        putValue("$path/waiting/d", "dddd")

        logger.info { "After putValues:" }
        logger.info { getChildren(path).asString }
        logger.info { getChildCount(path) }

        logger.info { "Election only:" }
        logger.info { getChildren("$path/election").asString }
        logger.info { getChildCount("$path/election") }

        logger.info { "Waiting only:" }
        logger.info { getChildren("$path/waiting").asString }
        logger.info { getChildCount("$path/waiting") }

        sleep(5.seconds)

        // Delete root
        deleteKey(path)

        // Delete children
        getChildrenKeys(path).forEach {
          logger.info {
            "Deleting key: $it"}
            deleteKey(it)
          }

          println("After removal:")
          println(getChildren(path).asString)
          println(getChildCount(path))

          sleep(5.seconds)
        }
      }
    }
  }
