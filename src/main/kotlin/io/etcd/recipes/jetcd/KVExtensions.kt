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

package io.etcd.recipes.jetcd

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption

// Put values
fun KV.putValue(keyname: String, keyval: String): PutResponse = put(keyname.asByteSequence, keyval.asByteSequence).get()

fun KV.putValue(keyname: String, keyval: Int): PutResponse = put(keyname.asByteSequence, keyval.asByteSequence).get()

fun KV.putValue(keyname: String, keyval: Long): PutResponse = put(keyname.asByteSequence, keyval.asByteSequence).get()

fun KV.putValue(keyname: String, keyval: String, option: PutOption): PutResponse =
    put(keyname.asByteSequence, keyval.asByteSequence, option).get()

fun KV.putValue(keyname: String, keyval: Int, option: PutOption): PutResponse =
    put(keyname.asByteSequence, keyval.asByteSequence, option).get()

fun KV.putValue(keyname: String, keyval: Long, option: PutOption): PutResponse =
    put(keyname.asByteSequence, keyval.asByteSequence, option).get()

fun Lazy<KV>.putValue(keyname: String, keyval: String, option: PutOption): PutResponse =
    value.putValue(keyname, keyval, option)

fun Lazy<KV>.putValue(keyname: String, keyval: Int, option: PutOption): PutResponse =
    value.putValue(keyname, keyval, option)

fun Lazy<KV>.putValue(keyname: String, keyval: Long, option: PutOption): PutResponse =
    value.putValue(keyname, keyval, option)

// Delete keys
fun KV.delete(vararg keynames: String) = keynames.forEach { delete(it) }

fun Lazy<KV>.delete(keyname: String): DeleteResponse = value.delete(keyname)

fun KV.delete(keyname: String): DeleteResponse = delete(keyname.asByteSequence).get()

// Get responses
fun KV.getResponse(keyname: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    get(keyname.asByteSequence, option).get()

fun Lazy<KV>.getResponse(keyname: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    value.getResponse(keyname, option)

// Get children key value pairs
private val String.asPrefixGetOption get() = GetOption.newBuilder().withPrefix(asByteSequence).build()

fun KV.getKeyValues(keyname: String, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyname, getOption).kvs.map { it.key.asString to it.value }

fun KV.getKeyValues(keyname: String): List<Pair<String, ByteSequence>> {
    val adjustedKey = keyname.ensureTrailing("/")
    return getKeyValues(adjustedKey, adjustedKey.asPrefixGetOption)
}

fun Lazy<KV>.getKeyValues(keyname: String): List<Pair<String, ByteSequence>> = value.getKeyValues(keyname)

fun KV.getKeys(keyname: String): List<String> = getKeyValues(keyname).keys

fun KV.getValues(keyname: String): List<ByteSequence> = getKeyValues(keyname).values

fun Lazy<KV>.getKeys(keyname: String): List<String> = value.getKeys(keyname)

fun Lazy<KV>.getValues(keyname: String): List<ByteSequence> = value.getValues(keyname)

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
fun KV.isKeyPresent(keyname: String) = !(transaction { If(equalTo(keyname, CmpTarget.version(0))) }.isSucceeded)

fun KV.isKeyNotPresent(keyname: String) = !isKeyPresent(keyname)

fun Lazy<KV>.isKeyPresent(keyname: String) = value.isKeyPresent(keyname)

fun Lazy<KV>.isKeyNotPresent(keyname: String) = value.isKeyNotPresent(keyname)

// Count children keys
fun KV.count(keyname: String): Long {
    val adjustedKey = keyname.ensureTrailing("/")
    val option = GetOption.newBuilder().withPrefix(adjustedKey.asByteSequence).withCountOnly(true).build()
    return getResponse(adjustedKey, option).count
}

fun Lazy<KV>.count(keyname: String): Long = value.count(keyname)