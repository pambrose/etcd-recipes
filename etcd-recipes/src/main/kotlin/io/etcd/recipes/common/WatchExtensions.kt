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

@file:JvmName("WatchUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.jetcd.watch.WatchResponse
import java.util.concurrent.CountDownLatch

val WatchEvent.keyAsString get() = keyValue.key.asString
val WatchEvent.keyAsInt get() = keyValue.key.asInt
val WatchEvent.keyAsLong get() = keyValue.key.asLong

val WatchEvent.valueAsString get() = keyValue.value.asString
val WatchEvent.valueAsInt get() = keyValue.value.asInt
val WatchEvent.valueAsLong get() = keyValue.value.asLong

//fun WatchOption.Builder.withPrefix(prefix: String): WatchOption.Builder = withPrefix(prefix.asByteSequence)

@JvmOverloads
fun Client.watcher(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  block: (WatchResponse) -> Unit
): Watch.Watcher =
  watchClient.watch(keyName.asByteSequence, option) { block(it) }

@JvmOverloads
fun <T> Client.withWatcher(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  block: (WatchResponse) -> Unit,
  receiver: Watch.Watcher.() -> T
): T = watcher(keyName, option, block).use { it.receiver() }

@JvmOverloads
fun Client.watcherWithLatch(
  keyName: String,
  endWatchLatch: CountDownLatch,
  onPut: (WatchEvent) -> Unit,
  onDelete: (WatchEvent) -> Unit,
  option: WatchOption = WatchOption.DEFAULT
) {
  withWatcher(keyName,
              option,
              { watchResponse ->
                watchResponse.events
                  .forEach { event ->
                    when (event.eventType) {
                      EventType.PUT -> onPut(event)
                      EventType.DELETE -> onDelete(event)
                      EventType.UNRECOGNIZED -> { // Ignore
                      }
                      else -> { // Ignore
                      }
                    }
                  }
              }) {
    endWatchLatch.await()
  }
}

private val nullWatchOption: WatchOption = watchOption { withRange(ByteSequence.from(ByteArray(1))) }