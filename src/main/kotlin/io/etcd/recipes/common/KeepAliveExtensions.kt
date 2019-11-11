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

@file:JvmName("KeepAliveUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import kotlin.time.Duration
import kotlin.time.seconds

fun Client.putValueWithKeepAlive(keyName: String, keyval: String, ttlSecs: Long, block: () -> Unit) =
    putValueWithKeepAlive(keyName, keyval, ttlSecs.seconds, block)

fun Client.putValueWithKeepAlive(keyName: String, keyval: Int, ttlSecs: Long, block: () -> Unit) =
    putValueWithKeepAlive(keyName, keyval, ttlSecs.seconds, block)

fun Client.putValueWithKeepAlive(keyName: String, keyval: Long, ttlSecs: Long, block: () -> Unit) =
    putValueWithKeepAlive(keyName, keyval, ttlSecs.seconds, block)

fun Client.putValueWithKeepAlive(keyName: String, keyval: ByteSequence, ttlSecs: Long, block: () -> Unit) =
    putValuesWithKeepAlive(listOf(keyName to keyval), ttlSecs, block)

fun Client.putValueWithKeepAlive(keyName: String, keyval: String, ttl: Duration, block: () -> Unit) =
    putValueWithKeepAlive(keyName, keyval.asByteSequence, ttl, block)

fun Client.putValueWithKeepAlive(keyName: String, keyval: Int, ttl: Duration, block: () -> Unit) =
    putValueWithKeepAlive(keyName, keyval.asByteSequence, ttl, block)

fun Client.putValueWithKeepAlive(keyName: String,
                                 keyval: Long,
                                 ttl: Duration,
                                 block: () -> Unit) =
    putValueWithKeepAlive(keyName, keyval.asByteSequence, ttl, block)

fun Client.putValueWithKeepAlive(keyName: String,
                                 keyval: ByteSequence,
                                 ttl: Duration,
                                 block: () -> Unit) =
    putValuesWithKeepAlive(listOf(keyName to keyval), ttl, block)

fun Client.putValuesWithKeepAlive(kvs: Collection<Pair<String, ByteSequence>>, ttlSecs: Long, block: () -> Unit) =
    putValuesWithKeepAlive(kvs, ttlSecs.seconds, block)

fun Client.putValuesWithKeepAlive(kvs: Collection<Pair<String, ByteSequence>>,
                                  ttl: Duration,
                                  block: () -> Unit) {
    val lease = leaseGrant(ttl)
    for (kv in kvs)
        putValue(kv.first, kv.second, putOption { withLeaseId(lease.id) })

    keepAliveWith(lease) { block() }
}
