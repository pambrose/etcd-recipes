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

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchResponse
import java.util.*

val KeyValue.asPair: Pair<String, ByteSequence> get() = key.asString to value

val String.asRangeWatchOption: WatchOption
    get() {
        val prefixEnd: ByteSequence = prefixEndOf(this)
        return WatchOption.newBuilder().withRange(prefixEnd).build()
    }

fun Lazy<Watch>.watcher(keyname: String,
                        option: WatchOption = WatchOption.DEFAULT,
                        block: (WatchResponse) -> Unit): Watch.Watcher = value.watcher(keyname, option, block)

fun Watch.watcher(keyname: String,
                  option: WatchOption = WatchOption.DEFAULT,
                  block: (WatchResponse) -> Unit): Watch.Watcher = watch(keyname.asByteSequence, option) { block(it) }

private val nullWatchOption: WatchOption = WatchOption.newBuilder().withRange(ByteSequence.from(ByteArray(1))).build()

private val NO_PREFIX_END = byteArrayOf(0)

// from io.etcd.jetcd.options.OptionsUtil.prefixEndOf()
internal fun prefixEndOf(prefix: String): ByteSequence {
    val endKey = prefix.asByteSequence.bytes.clone()
    for (i in endKey.indices.reversed()) {
        if (endKey[i] < 0xff) {
            endKey[i] = (endKey[i] + 1).toByte()
            return ByteSequence.from(Arrays.copyOf(endKey, i + 1))
        }
    }
    return ByteSequence.from(NO_PREFIX_END)
}

