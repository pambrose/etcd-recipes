/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

@file:JvmName("KVUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption

@JvmOverloads
fun Client.putValue(keyName: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): PutResponse =
    kvClient.put(keyName.asByteSequence, keyval, option).get()

@JvmOverloads
fun Client.putValue(keyName: String, keyval: String, option: PutOption = PutOption.DEFAULT): PutResponse =
    kvClient.put(keyName.asByteSequence, keyval.asByteSequence, option).get()

@JvmOverloads
fun Client.putValue(keyName: String, keyval: Int, option: PutOption = PutOption.DEFAULT): PutResponse =
    kvClient.put(keyName.asByteSequence, keyval.asByteSequence, option).get()

@JvmOverloads
fun Client.putValue(keyName: String, keyval: Long, option: PutOption = PutOption.DEFAULT): PutResponse =
    kvClient.put(keyName.asByteSequence, keyval.asByteSequence, option).get()

// Delete keys
fun Client.deleteKeys(vararg keyNames: String) = keyNames.forEach { deleteKey(it) }

fun Client.deleteKey(keyName: String): DeleteResponse = kvClient.delete(keyName.asByteSequence).get()

// Get responses
internal fun Client.getResponse(keyName: ByteSequence,
                                option: GetOption = GetOption.DEFAULT,
                                iteration: Int = 0): GetResponse {
    val response = kvClient.get(keyName, option).get()
    return if (response.kvs.isEmpty() && response.isMore) {
        if (iteration == 10)
            throw EtcdRecipeRuntimeException("Unable to fulfill call to getResponse after multiple attempts")
        getResponse(keyName, option, iteration + 1)
    } else {
        response
    }
}

@JvmOverloads
fun Client.getResponse(keyName: String, option: GetOption = GetOption.DEFAULT): GetResponse =
    getResponse(keyName.asByteSequence, option)

// Get children key value pairs
fun Client.getKeyValuePairs(keyName: ByteSequence, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyName, getOption).kvs.map { it.key.asString to it.value }

fun Client.getKeyValuePairs(keyName: String, getOption: GetOption): List<Pair<String, ByteSequence>> =
    getResponse(keyName, getOption).kvs.map { it.key.asString to it.value }

// Get single key value
fun Client.getValue(keyName: String): ByteSequence? {
    val getOption = getOption { withLimit(1) }
    return getResponse(keyName, getOption).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value
}

// Get single key value with default
fun Client.getValue(keyName: String, default: String): String = getValue(keyName)?.asString ?: default

fun Client.getValue(keyName: String, default: Int): Int = getValue(keyName)?.asInt ?: default

fun Client.getValue(keyName: String, default: Long): Long = getValue(keyName)?.asLong ?: default

// Key checking
fun Client.isKeyPresent(keyName: String) = transaction { If(keyName.doesExist) }.isSucceeded

fun Client.isKeyNotPresent(keyName: String) = !isKeyPresent(keyName)