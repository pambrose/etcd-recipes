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

@file:JvmName("KVUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption
import kotlin.time.Duration
import kotlin.time.seconds

@JvmOverloads
fun KV.putValue(keyname: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyname.asByteSequence, keyval, option).get()

@JvmOverloads
fun KV.putValue(keyname: String, keyval: String, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyname.asByteSequence, keyval.asByteSequence, option).get()

@JvmOverloads
fun KV.putValue(keyname: String, keyval: Int, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyname.asByteSequence, keyval.asByteSequence, option).get()

@JvmOverloads
fun KV.putValue(keyname: String, keyval: Long, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyname.asByteSequence, keyval.asByteSequence, option).get()

fun Lazy<KV>.putValue(keyname: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyname, keyval, option)

fun Lazy<KV>.putValue(keyname: String, keyval: String, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyname, keyval, option)

fun Lazy<KV>.putValue(keyname: String, keyval: Int, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyname, keyval, option)

fun Lazy<KV>.putValue(keyname: String, keyval: Long, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyname, keyval, option)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: String, ttlSecs: Long, block: () -> Unit) =
    putValueWithKeepAlive(client, keyname, keyval, ttlSecs.seconds, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: Int, ttlSecs: Long, block: () -> Unit) =
    putValueWithKeepAlive(client, keyname, keyval, ttlSecs.seconds, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: Long, ttlSecs: Long, block: () -> Unit) =
    putValueWithKeepAlive(client, keyname, keyval, ttlSecs.seconds, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: ByteSequence, ttlSecs: Long, block: () -> Unit) =
    putValuesWithKeepAlive(client, listOf(keyname to keyval), ttlSecs, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: String, ttl: Duration, block: () -> Unit) =
    putValueWithKeepAlive(client, keyname, keyval.asByteSequence, ttl, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: Int, ttl: Duration, block: () -> Unit) =
    putValueWithKeepAlive(client, keyname, keyval.asByteSequence, ttl, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: Long, ttl: Duration, block: () -> Unit) =
    putValueWithKeepAlive(client, keyname, keyval.asByteSequence, ttl, block)

fun KV.putValueWithKeepAlive(client: Client, keyname: String, keyval: ByteSequence, ttl: Duration, block: () -> Unit) =
    putValuesWithKeepAlive(client, listOf(keyname to keyval), ttl, block)

fun KV.putValuesWithKeepAlive(client: Client,
                              kvs: Collection<Pair<String, ByteSequence>>,
                              ttlSecs: Long,
                              block: () -> Unit) =
    putValuesWithKeepAlive(client, kvs, ttlSecs.seconds, block)

fun KV.putValuesWithKeepAlive(client: Client,
                              kvs: Collection<Pair<String, ByteSequence>>,
                              ttl: Duration,
                              block: () -> Unit) =
    client.withLeaseClient { leaseClient ->
        val lease = leaseClient.grant(ttl).get()
        for (kv in kvs)
            putValue(kv.first, kv.second, lease.asPutOption)

        leaseClient.keepAliveWith(lease) {
            block()
        }
    }

// Delete keys
fun KV.delete(vararg keynames: String) = keynames.forEach { delete(it) }

fun Lazy<KV>.delete(keyname: String): DeleteResponse = value.delete(keyname)

fun KV.delete(keyname: String): DeleteResponse = delete(keyname.asByteSequence).get()

fun KV.deleteChildren(parentKeyName: String): List<String> {
    val keys = getChildrenKeys(parentKeyName)
    for (key in keys)
        delete(key)
    return keys
}

// Get responses
@JvmOverloads
fun KV.getResponse(keyname: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    get(keyname.asByteSequence, option).get()

@JvmOverloads
fun Lazy<KV>.getResponse(keyname: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    value.getResponse(keyname, option)

// Get children key value pairs
private val String.asPrefixGetOption get() = GetOption.newBuilder().withPrefix(asByteSequence).build()

fun KV.getKeyValuePairs(keyname: String, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyname, getOption).kvs.map { it.key.asString to it.value }

fun KV.getChildren(parentKeyName: String): List<Pair<String, ByteSequence>> {
    val adjustedKey = parentKeyName.ensureTrailing("/")
    return getKeyValuePairs(adjustedKey, adjustedKey.asPrefixGetOption)
}

fun Lazy<KV>.getChildren(parentKeyName: String): List<Pair<String, ByteSequence>> = value.getChildren(parentKeyName)

fun KV.getChildrenKeys(parentKeyName: String): List<String> = getChildren(parentKeyName).keys

fun KV.getChildrenValues(parentKeyName: String): List<ByteSequence> = getChildren(parentKeyName).values

fun Lazy<KV>.getChildrenKeys(parentKeyName: String): List<String> = value.getChildrenKeys(parentKeyName)

fun Lazy<KV>.getChildrenValues(parentKeyName: String): List<ByteSequence> = value.getChildrenValues(parentKeyName)

// Get single key value
fun KV.getValue(keyname: String): ByteSequence? =
    getResponse(keyname).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value

fun Lazy<KV>.getValue(keyname: String): ByteSequence? = value.getValue(keyname)

// Get single key value with default
fun KV.getValue(keyname: String, defaultVal: String): String = getValue(keyname)?.asString ?: defaultVal

fun KV.getValue(keyname: String, defaultVal: Int): Int = getValue(keyname)?.asInt ?: defaultVal

fun KV.getValue(keyname: String, defaultVal: Long): Long = getValue(keyname)?.asLong ?: defaultVal

fun Lazy<KV>.getValue(keyname: String, defaultVal: String): String = value.getValue(keyname, defaultVal)

fun Lazy<KV>.getValue(keyname: String, defaultVal: Int): Int = value.getValue(keyname, defaultVal)

fun Lazy<KV>.getValue(keyname: String, defaultVal: Long): Long = value.getValue(keyname, defaultVal)

// Key checking
fun KV.isKeyPresent(keyname: String) = transaction { If(keyname.doesExist) }.isSucceeded

fun KV.isKeyNotPresent(keyname: String) = !isKeyPresent(keyname)

fun Lazy<KV>.isKeyPresent(keyname: String) = value.isKeyPresent(keyname)

fun Lazy<KV>.isKeyNotPresent(keyname: String) = value.isKeyNotPresent(keyname)

// Count children keys
fun KV.countChildren(parentKeyName: String): Long {
    val adjustedKey = parentKeyName.ensureTrailing("/")
    val option = GetOption.newBuilder().withPrefix(adjustedKey.asByteSequence).withCountOnly(true).build()
    return getResponse(adjustedKey, option).count
}

fun Lazy<KV>.countChildren(keyname: String): Long = value.countChildren(keyname)