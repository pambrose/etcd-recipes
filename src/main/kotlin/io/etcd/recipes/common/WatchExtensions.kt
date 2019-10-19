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

@file:JvmName("WatchUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.jetcd.watch.WatchResponse
import java.util.concurrent.CountDownLatch

val KeyValue.asPair: Pair<String, ByteSequence> get() = Pair(key.asString, value)

val WatchEvent.keyAsString get() = keyValue.key.asString
val WatchEvent.keyAsInt get() = keyValue.key.asInt
val WatchEvent.keyAsLong get() = keyValue.key.asLong

val WatchEvent.valueAsString get() = keyValue.value.asString
val WatchEvent.valueAsInt get() = keyValue.value.asInt
val WatchEvent.valueAsLong get() = keyValue.value.asLong

val String.asPrefixWatchOption: WatchOption
    get() = WatchOption.newBuilder().withPrefix(asByteSequence).build()

fun Lazy<Watch>.watcher(keyname: String,
                        option: WatchOption = WatchOption.DEFAULT,
                        block: (WatchResponse) -> Unit): Watch.Watcher = value.watcher(keyname, option, block)

@JvmOverloads
fun Watch.watcher(keyname: String,
                  option: WatchOption = WatchOption.DEFAULT,
                  block: (WatchResponse) -> Unit): Watch.Watcher = watch(keyname.asByteSequence, option) { block(it) }

@JvmOverloads
fun Watch.watcher(keyname: String,
                  endWatchLatch: CountDownLatch,
                  onPut: (WatchEvent) -> Unit,
                  onDelete: (WatchEvent) -> Unit,
                  option: WatchOption = WatchOption.DEFAULT): CountDownLatch {
    watch(keyname.asByteSequence, option) { watchResponse ->
        watchResponse.events
            .forEach { event ->
                when (event.eventType) {
                    EventType.PUT          -> onPut(event)
                    EventType.DELETE       -> onDelete(event)
                    EventType.UNRECOGNIZED -> { // Ignore
                    }
                    else                   -> { // Ignore
                    }
                }
            }
    }.use {
        endWatchLatch.await()
    }
    return endWatchLatch
}

private val nullWatchOption: WatchOption = WatchOption.newBuilder().withRange(ByteSequence.from(ByteArray(1))).build()