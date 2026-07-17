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

import com.pambrose.common.util.ensureSuffix
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.deleteOption
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.keys
import io.etcd.recipes.common.values

/** Suspending twin of `getChildren`: all key/value pairs under [keyName]. */
suspend fun Client.awaitGetChildren(
  keyName: String,
  target: GetOption.SortTarget = GetOption.SortTarget.KEY,
  order: SortOrder = SortOrder.ASCEND,
  keysOnly: Boolean = false,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<Pair<String, ByteSequence>> {
  val trailingKey = keyName.ensureSuffix("/")
  val getOption =
    getOption {
      isPrefix(true)
      withSortField(target)
      withSortOrder(order)
      withKeysOnly(keysOnly)
    }
  return awaitGetKeyValuePairs(trailingKey, getOption, rpc)
}

private suspend fun Client.awaitGetSingleChild(
  keyName: String,
  target: GetOption.SortTarget,
  order: SortOrder,
  rpc: RpcResilience,
): GetResponse {
  val trailingKey = keyName.ensureSuffix("/")
  val getOption =
    getOption {
      isPrefix(true)
      withSortField(target)
      withSortOrder(order)
      withLimit(1)
    }
  return awaitGetResponse(trailingKey, getOption, rpc)
}

/** Suspending twin of `getFirstChild`. */
suspend fun Client.awaitGetFirstChild(
  keyName: String,
  target: GetOption.SortTarget,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse = awaitGetSingleChild(keyName, target, SortOrder.ASCEND, rpc)

/** Suspending twin of `getLastChild`. */
suspend fun Client.awaitGetLastChild(
  keyName: String,
  target: GetOption.SortTarget,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse = awaitGetSingleChild(keyName, target, SortOrder.DESCEND, rpc)

/** Suspending twin of `getChildrenKeys`. */
suspend fun Client.awaitGetChildrenKeys(
  keyName: String,
  target: GetOption.SortTarget = GetOption.SortTarget.KEY,
  order: SortOrder = SortOrder.ASCEND,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<String> = awaitGetChildren(keyName, target, order, true, rpc).keys

/** Suspending twin of `getChildrenValues`. */
suspend fun Client.awaitGetChildrenValues(
  keyName: String,
  target: GetOption.SortTarget = GetOption.SortTarget.KEY,
  order: SortOrder = SortOrder.ASCEND,
): List<ByteSequence> = awaitGetChildren(keyName, target, order).values

/** Suspending twin of `getChildCount`. */
suspend fun Client.awaitGetChildCount(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Long {
  val trailingKey = keyName.ensureSuffix("/")
  val getOption =
    getOption {
      isPrefix(true)
      withCountOnly(true)
    }
  return awaitGetResponse(trailingKey.asByteSequence, getOption, 0, rpc).count
}

/** Suspending twin of `deleteChildren`: deletes everything under [keyName], returning the deleted keys. */
suspend fun Client.awaitDeleteChildren(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<String> {
  val trailingKey = keyName.ensureSuffix("/")
  val deleteOption =
    deleteOption {
      isPrefix(true)
      withPrevKV(true)
    }
  return suspendRetryRpc(rpc, "deleteChildren($keyName)") { kvClient.delete(trailingKey.asByteSequence, deleteOption) }
    .prevKvs.map { it.key.asString }
}
