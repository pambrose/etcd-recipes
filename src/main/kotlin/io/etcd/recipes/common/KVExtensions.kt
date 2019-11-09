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

fun KV.deleteChildren(keyName: String): List<String> {
    val keys = getChildrenKeys(keyName)
    for (key in keys)
        delete(key)
    return keys
}

// Get responses
private fun KV.getResponse(keyName: ByteSequence,
                           option: GetOption = GetOption.DEFAULT,
                           iteration: Int = 0): GetResponse {
    val response = get(keyName, option).get()
    if (response.kvs.isEmpty() && response.isMore) {
        if (iteration == 10)
            throw EtcdRecipeRuntimeException("Unable to fulfill call to getResponse after multiple attempts")
        return getResponse(keyName, option, iteration + 1)
    } else {
        return response
    }
}

@JvmOverloads
fun KV.getResponse(keyName: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    getResponse(keyName.asByteSequence, option)

@JvmOverloads
fun Lazy<KV>.getResponse(keyName: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    value.getResponse(keyName, option)

// Get children key value pairs
fun KV.getKeyValuePairs(keyName: ByteSequence, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyName, getOption).kvs.map { it.key.asString to it.value }

fun KV.getKeyValuePairs(keyName: String, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyName, getOption).kvs.map { it.key.asString to it.value }

@JvmOverloads
fun KV.getChildren(keyName: String, keysOnly: Boolean = false): List<Pair<String, ByteSequence>> {
    val trailingKey = keyName.ensureSuffix("/").asByteSequence
    val getOption: GetOption =
        getOption {
            withPrefix(trailingKey)
            withKeysOnly(keysOnly)
        }
    return getKeyValuePairs(trailingKey, getOption)
}

fun KV.getOldestChild(keyName: String): GetResponse {
    val trailingKey = keyName.ensureSuffix("/").asByteSequence
    val getOption: GetOption =
        getOption {
            withPrefix(trailingKey)
            withSortField(SortTarget.MOD)
            withSortOrder(SortOrder.ASCEND)
            withLimit(1)
        }
    return getResponse(trailingKey, getOption)
}

fun KV.getLastChildByKey(keyName: String): GetResponse {
    val trailingKey = keyName.ensureSuffix("/").asByteSequence
    val getOption: GetOption =
        getOption {
            withPrefix(trailingKey)
            withSortField(SortTarget.KEY)
            withSortOrder(SortOrder.DESCEND)
            withLimit(1)
        }
    return getResponse(trailingKey, getOption)
}

fun Lazy<KV>.getChildren(keyName: String): List<Pair<String, ByteSequence>> = value.getChildren(keyName)

fun Lazy<KV>.getChildren(keyName: ByteSequence): List<Pair<String, ByteSequence>> = getChildren(keyName.asString)

fun Lazy<KV>.getOldestChild(keyName: String): GetResponse = value.getOldestChild(keyName)

fun Lazy<KV>.getLastChildByKey(keyName: String): GetResponse = value.getLastChildByKey(keyName)

fun KV.getChildrenKeys(keyName: String): List<String> = getChildren(keyName, true).keys

fun KV.getChildrenValues(keyName: String): List<ByteSequence> = getChildren(keyName).values

fun Lazy<KV>.getChildrenKeys(keyName: String): List<String> = value.getChildrenKeys(keyName)

fun Lazy<KV>.getChildrenValues(keyName: String): List<ByteSequence> = value.getChildrenValues(keyName)

// Get single key value
fun KV.getValue(keyName: String): ByteSequence? =
    getResponse(keyName).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value

fun Lazy<KV>.getValue(keyName: String): ByteSequence? = value.getValue(keyName)

// Get single key value with default
fun KV.getValue(keyName: String, default: String): String = getValue(keyName)?.asString ?: default

fun KV.getValue(keyName: String, default: Int): Int = getValue(keyName)?.asInt ?: default

fun KV.getValue(keyName: String, default: Long): Long = getValue(keyName)?.asLong ?: default

fun Lazy<KV>.getValue(keyName: String, default: String): String = value.getValue(keyName, default)

fun Lazy<KV>.getValue(keyName: String, default: Int): Int = value.getValue(keyName, default)

fun Lazy<KV>.getValue(keyName: String, default: Long): Long = value.getValue(keyName, default)

// Key checking
fun KV.isKeyPresent(keyName: String) = transaction { If(keyName.doesExist) }.isSucceeded

fun KV.isKeyNotPresent(keyName: String) = !isKeyPresent(keyName)

fun Lazy<KV>.isKeyPresent(keyName: String) = value.isKeyPresent(keyName)

fun Lazy<KV>.isKeyNotPresent(keyName: String) = value.isKeyNotPresent(keyName)

// Count children keys
fun KV.getChildrenCount(keyName: String): Long {
    val trailingKey = keyName.ensureSuffix("/").asByteSequence
    val getOption: GetOption =
        getOption {
            withPrefix(trailingKey)
            withCountOnly(true)
        }
    return getResponse(trailingKey, getOption).count
}

fun Lazy<KV>.getChildrenCount(keyName: String): Long = value.getChildrenCount(keyName)