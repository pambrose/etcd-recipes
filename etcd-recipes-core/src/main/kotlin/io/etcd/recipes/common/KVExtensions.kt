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

@file:JvmName("KVUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.CompactResponse
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.options.CompactOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption

private const val MAX_GET_ATTEMPTS = 10

// Puts are retried on retriable statuses: values here are last-writer-wins, so a
// duplicate apply from an ambiguous first attempt is harmless. CAS puts go through
// transaction { }, which is never retried.
@JvmOverloads
fun Client.putValue(
  keyName: String,
  keyval: ByteSequence,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = retryRpc(rpc, "putValue($keyName)") { kvClient.put(keyName.asByteSequence, keyval, option) }

@JvmOverloads
fun Client.putValue(
  keyName: String,
  keyval: String,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = putValue(keyName, keyval.asByteSequence, option, rpc)

@JvmOverloads
fun Client.putValue(
  keyName: String,
  keyval: Int,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = putValue(keyName, keyval.asByteSequence, option, rpc)

@JvmOverloads
fun Client.putValue(
  keyName: String,
  keyval: Long,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = putValue(keyName, keyval.asByteSequence, option, rpc)

// Delete keys
fun Client.deleteKeys(vararg keyNames: String) = keyNames.forEach { deleteKey(it) }

@JvmOverloads
fun Client.deleteKey(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): DeleteResponse = retryRpc(rpc, "deleteKey($keyName)") { kvClient.delete(keyName.asByteSequence) }

// Get responses
internal fun Client.getResponse(
  keyName: ByteSequence,
  option: GetOption = GetOption.DEFAULT,
  iteration: Int = 0,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse {
  val response = retryRpc(rpc, "getResponse(${keyName.asString})") { kvClient.get(keyName, option) }
  return if (response.kvs.isEmpty() && response.isMore) {
    if (iteration >= MAX_GET_ATTEMPTS)
      throw EtcdRecipeRuntimeException(
        "Unable to fulfill call to getResponse for key ${keyName.asString} after $iteration attempts",
      )
    getResponse(keyName, option, iteration + 1, rpc)
  } else {
    response
  }
}

@JvmOverloads
fun Client.getResponse(
  keyName: String,
  option: GetOption = GetOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse = getResponse(keyName.asByteSequence, option, 0, rpc)

// Get children key value pairs
@JvmOverloads
fun Client.getKeyValuePairs(
  keyName: ByteSequence,
  getOption: GetOption,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<Pair<String, ByteSequence>> = getResponse(keyName, getOption, 0, rpc).kvs.map { it.key.asString to it.value }

@JvmOverloads
fun Client.getKeyValuePairs(
  keyName: String,
  getOption: GetOption,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<Pair<String, ByteSequence>> = getKeyValuePairs(keyName.asByteSequence, getOption, rpc)

// Get single key value
@JvmOverloads
fun Client.getValue(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): ByteSequence? {
  val getOption = getOption { withLimit(1) }
  return getResponse(keyName, getOption, rpc).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value
}

// Get single key value with default
@JvmOverloads
fun Client.getValue(
  keyName: String,
  default: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): String = getValue(keyName, rpc)?.asString ?: default

@JvmOverloads
fun Client.getValue(
  keyName: String,
  default: Int,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Int = getValue(keyName, rpc)?.asInt ?: default

@JvmOverloads
fun Client.getValue(
  keyName: String,
  default: Long,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Long = getValue(keyName, rpc)?.asLong ?: default

// Compaction
@JvmOverloads
fun Client.compact(
  revision: Long,
  option: CompactOption = CompactOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): CompactResponse = retryRpc(rpc, "compact($revision)") { kvClient.compact(revision, option) }

// Key checking
@JvmOverloads
fun Client.isKeyPresent(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
) = transaction(rpc) { If(keyName.doesExist) }.isSucceeded

@JvmOverloads
fun Client.isKeyNotPresent(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
) = !isKeyPresent(keyName, rpc)
