/*
 * Copyright © 2026 Paul Ambrose
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

import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.jetcd.watch.WatchResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val WatchEvent.keyAsString get() = keyValue.key.asString
val WatchEvent.keyAsInt get() = keyValue.key.asInt
val WatchEvent.keyAsLong get() = keyValue.key.asLong

val WatchEvent.valueAsString get() = keyValue.value.asString
val WatchEvent.valueAsInt get() = keyValue.value.asInt
val WatchEvent.valueAsLong get() = keyValue.value.asLong

// fun WatchOption.Builder.withPrefix(prefix: String): WatchOption.Builder = withPrefix(prefix.asByteSequence)

@JvmOverloads
fun Client.watcher(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  block: (WatchResponse) -> Unit,
): Watch.Watcher {
  // jetcd 0.7+ delivers watch events on its Vert.x gRPC event loop. Anything
  // the callback does that needs another gRPC response — or contends with a
  // lock the caller is holding while waiting on a gRPC response — would
  // deadlock the event loop. Hop the callback onto a dedicated single-thread
  // executor so blocking calls inside it stay off the gRPC threads.
  val dispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "etcd-watch-dispatcher").apply { isDaemon = true }
  }
  val delegate = watchClient.watch(keyName.asByteSequence, option) { response ->
    dispatcher.execute { block(response) }
  }
  return DispatchingWatcher(delegate, dispatcher)
}

private class DispatchingWatcher(
  private val delegate: Watch.Watcher,
  private val dispatcher: ExecutorService,
) : Watch.Watcher by delegate {
  override fun close() {
    delegate.close()
    dispatcher.shutdown()
    // shutdown() refuses new tasks but does not wait for the currently-running
    // callback task. Without awaiting, the user's `block` could still be
    // executing on the dispatcher thread after withWatcher's receiver has
    // returned — touching state the caller assumes is no longer in use. A
    // short bounded wait makes the close-then-touch contract real without
    // turning into a blocker on a misbehaving callback.
    dispatcher.awaitTermination(5, TimeUnit.SECONDS)
  }
}

@JvmOverloads
fun <T> Client.withWatcher(
  keyName: String,
  option: WatchOption = WatchOption.DEFAULT,
  block: (WatchResponse) -> Unit,
  receiver: Watch.Watcher.() -> T,
): T =
  watcher(keyName, option, block)
    .use { watcher ->
      watcher.receiver()
    }

@JvmOverloads
fun Client.watcherWithLatch(
  keyName: String,
  endWatchLatch: CountDownLatch,
  onPut: (WatchEvent) -> Unit,
  onDelete: (WatchEvent) -> Unit,
  option: WatchOption = WatchOption.DEFAULT,
) {
  withWatcher(
    keyName,
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
    },
  ) {
    endWatchLatch.await()
  }
}
