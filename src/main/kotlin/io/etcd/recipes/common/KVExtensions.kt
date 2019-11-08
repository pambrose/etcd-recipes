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
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.jetcd.options.PutOption

@JvmOverloads
fun KV.putValue(keyName: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyName.asByteSequence, keyval, option).get()

@JvmOverloads
fun KV.putValue(keyName: String, keyval: String, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyName.asByteSequence, keyval.asByteSequence, option).get()

@JvmOverloads
fun KV.putValue(keyName: String, keyval: Int, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyName.asByteSequence, keyval.asByteSequence, option).get()

@JvmOverloads
fun KV.putValue(keyName: String, keyval: Long, option: PutOption = PutOption.DEFAULT): PutResponse =
    put(keyName.asByteSequence, keyval.asByteSequence, option).get()

fun Lazy<KV>.putValue(keyName: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyName, keyval, option)

fun Lazy<KV>.putValue(keyName: String, keyval: String, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyName, keyval, option)

fun Lazy<KV>.putValue(keyName: String, keyval: Int, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyName, keyval, option)

fun Lazy<KV>.putValue(keyName: String, keyval: Long, option: PutOption = PutOption.DEFAULT): PutResponse =
    value.putValue(keyName, keyval, option)

// Delete keys
fun KV.delete(vararg keyNames: String) = keyNames.forEach { delete(it) }

fun Lazy<KV>.delete(keyName: String): DeleteResponse = value.delete(keyName)

fun KV.delete(keyName: String): DeleteResponse = delete(keyName.asByteSequence).get()

fun KV.deleteChildren(parentKeyName: String): List<String> {
    val keys = getChildrenKeys(parentKeyName)
    for (key in keys)
        delete(key)
    return keys
}

// Get responses
@JvmOverloads
fun KV.getResponse(keyName: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    get(keyName.asByteSequence, option).get()

@JvmOverloads
fun Lazy<KV>.getResponse(keyName: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    value.getResponse(keyName, option)

// Get children key value pairs
fun KV.getKeyValuePairs(keyName: String, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyName, getOption).kvs.map { it.key.asString to it.value }

@JvmOverloads
fun KV.getChildren(parentKeyName: String, keysOnly: Boolean = false): List<Pair<String, ByteSequence>> {
    val adjustedKey = parentKeyName.ensureSuffix("/")
    val option = GetOption.newBuilder().withPrefix(adjustedKey.asByteSequence).withKeysOnly(keysOnly).build()
    return getKeyValuePairs(adjustedKey, option)
}

fun KV.getOldestChild(parentKeyName: String): List<Pair<String, ByteSequence>> {
    val adjustedKey = parentKeyName.ensureSuffix("/")
    val option =
        GetOption
            .newBuilder()
            .withPrefix(adjustedKey.asByteSequence)
            .withSortField(SortTarget.VERSION)
            .withSortOrder(SortOrder.DESCEND)
            .withLimit(1)
            .build()
    return getKeyValuePairs(adjustedKey, option)
}

fun Lazy<KV>.getChildren(parentKeyName: String): List<Pair<String, ByteSequence>> = value.getChildren(parentKeyName)

fun Lazy<KV>.getOldestChild(parentKeyName: String): List<Pair<String, ByteSequence>> =
    value.getOldestChild(parentKeyName)

fun KV.getChildrenKeys(parentKeyName: String): List<String> = getChildren(parentKeyName, true).keys

fun KV.getChildrenValues(parentKeyName: String): List<ByteSequence> = getChildren(parentKeyName).values

fun Lazy<KV>.getChildrenKeys(parentKeyName: String): List<String> = value.getChildrenKeys(parentKeyName)

fun Lazy<KV>.getChildrenValues(parentKeyName: String): List<ByteSequence> = value.getChildrenValues(parentKeyName)

// Get single key value
fun KV.getValue(keyName: String): ByteSequence? =
    getResponse(keyName).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value

fun Lazy<KV>.getValue(keyName: String): ByteSequence? = value.getValue(keyName)

// Get single key value with default
fun KV.getValue(keyName: String, defaultVal: String): String = getValue(keyName)?.asString ?: defaultVal

fun KV.getValue(keyName: String, defaultVal: Int): Int = getValue(keyName)?.asInt ?: defaultVal

fun KV.getValue(keyName: String, defaultVal: Long): Long = getValue(keyName)?.asLong ?: defaultVal

fun Lazy<KV>.getValue(keyName: String, defaultVal: String): String = value.getValue(keyName, defaultVal)

fun Lazy<KV>.getValue(keyName: String, defaultVal: Int): Int = value.getValue(keyName, defaultVal)

fun Lazy<KV>.getValue(keyName: String, defaultVal: Long): Long = value.getValue(keyName, defaultVal)

// Key checking
fun KV.isKeyPresent(keyName: String) = transaction { If(keyName.doesExist) }.isSucceeded

fun KV.isKeyNotPresent(keyName: String) = !isKeyPresent(keyName)

fun Lazy<KV>.isKeyPresent(keyName: String) = value.isKeyPresent(keyName)

fun Lazy<KV>.isKeyNotPresent(keyName: String) = value.isKeyNotPresent(keyName)

// Count children keys
fun KV.getChildrenCount(parentKeyName: String): Long {
    val adjustedKey = parentKeyName.ensureSuffix("/")
    val option = GetOption.newBuilder().withPrefix(adjustedKey.asByteSequence).withCountOnly(true).build()
    return getResponse(adjustedKey, option).count
}

fun Lazy<KV>.getChildrenCount(keyName: String): Long = value.getChildrenCount(keyName)