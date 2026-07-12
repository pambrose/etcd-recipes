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

package io.etcd.recipes.coroutines

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.CompactResponse
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.options.CompactOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.PutOption
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asInt
import io.etcd.recipes.common.asLong
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.getOption

private const val MAX_GET_ATTEMPTS = 10 // mirrors KVExtensions

/**
 * Suspending twin of `putValue`: last-writer-wins puts, retried on retriable
 * statuses (a duplicate apply from an ambiguous first attempt is harmless).
 * CAS puts go through [awaitTransaction], which is never retried.
 */
suspend fun Client.awaitPutValue(
  keyName: String,
  keyval: ByteSequence,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = suspendRetryRpc(rpc, "putValue($keyName)") { kvClient.put(keyName.asByteSequence, keyval, option) }

/** Suspending twin of `putValue` for String values. */
suspend fun Client.awaitPutValue(
  keyName: String,
  keyval: String,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = awaitPutValue(keyName, keyval.asByteSequence, option, rpc)

/** Suspending twin of `putValue` for Int values. */
suspend fun Client.awaitPutValue(
  keyName: String,
  keyval: Int,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = awaitPutValue(keyName, keyval.asByteSequence, option, rpc)

/** Suspending twin of `putValue` for Long values. */
suspend fun Client.awaitPutValue(
  keyName: String,
  keyval: Long,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = awaitPutValue(keyName, keyval.asByteSequence, option, rpc)

/** Suspending twin of `deleteKeys`. */
suspend fun Client.awaitDeleteKeys(vararg keyNames: String) {
  keyNames.forEach { awaitDeleteKey(it) }
}

/** Suspending twin of `deleteKey`. */
suspend fun Client.awaitDeleteKey(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): DeleteResponse = suspendRetryRpc(rpc, "deleteKey($keyName)") { kvClient.delete(keyName.asByteSequence) }

// Mirrors the blocking internal getResponse: re-fetches while etcd reports more
// data pending but returns no kvs.
internal suspend fun Client.awaitGetResponse(
  keyName: ByteSequence,
  option: GetOption = GetOption.DEFAULT,
  iteration: Int = 0,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse {
  val response = suspendRetryRpc(rpc, "getResponse(${keyName.asString})") { kvClient.get(keyName, option) }
  return if (response.kvs.isEmpty() && response.isMore) {
    if (iteration >= MAX_GET_ATTEMPTS)
      throw EtcdRecipeRuntimeException(
        "Unable to fulfill call to getResponse for key ${keyName.asString} after $iteration attempts",
      )
    awaitGetResponse(keyName, option, iteration + 1, rpc)
  } else {
    response
  }
}

/** Suspending twin of `getResponse`. */
suspend fun Client.awaitGetResponse(
  keyName: String,
  option: GetOption = GetOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse = awaitGetResponse(keyName.asByteSequence, option, 0, rpc)

/** Suspending twin of `getKeyValuePairs`. */
suspend fun Client.awaitGetKeyValuePairs(
  keyName: String,
  getOption: GetOption,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<Pair<String, ByteSequence>> =
  awaitGetResponse(keyName.asByteSequence, getOption, 0, rpc).kvs.map { it.key.asString to it.value }

/** Suspending twin of `getValue`. */
suspend fun Client.awaitGetValue(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): ByteSequence? {
  val getOption = getOption { withLimit(1) }
  return awaitGetResponse(keyName, getOption, rpc).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value
}

/** Suspending twin of `getValue` with a String default. */
suspend fun Client.awaitGetValue(
  keyName: String,
  default: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): String = awaitGetValue(keyName, rpc)?.asString ?: default

/** Suspending twin of `getValue` with an Int default. */
suspend fun Client.awaitGetValue(
  keyName: String,
  default: Int,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Int = awaitGetValue(keyName, rpc)?.asInt ?: default

/** Suspending twin of `getValue` with a Long default. */
suspend fun Client.awaitGetValue(
  keyName: String,
  default: Long,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Long = awaitGetValue(keyName, rpc)?.asLong ?: default

/** Suspending twin of `compact`. */
suspend fun Client.awaitCompact(
  revision: Long,
  option: CompactOption = CompactOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): CompactResponse = suspendRetryRpc(rpc, "compact($revision)") { kvClient.compact(revision, option) }

/** Suspending twin of `isKeyPresent`. */
suspend fun Client.awaitIsKeyPresent(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Boolean = awaitTransaction(rpc) { If(keyName.doesExist) }.isSucceeded

/** Suspending twin of `isKeyNotPresent`. */
suspend fun Client.awaitIsKeyNotPresent(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Boolean = !awaitIsKeyPresent(keyName, rpc)
