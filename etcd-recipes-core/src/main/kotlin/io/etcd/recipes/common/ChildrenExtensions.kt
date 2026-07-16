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

@file:JvmName("ChildrenUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import com.pambrose.common.util.ensureSuffix
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder

@JvmOverloads
fun Client.getChildren(
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
  return getKeyValuePairs(trailingKey, getOption, rpc)
}

@JvmOverloads
fun Client.getFirstChild(
  keyName: String,
  target: GetOption.SortTarget,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse = getSingleChild(keyName, target, SortOrder.ASCEND, rpc)

@JvmOverloads
fun Client.getLastChild(
  keyName: String,
  target: GetOption.SortTarget,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse = getSingleChild(keyName, target, SortOrder.DESCEND, rpc)

// fun GetOption.Builder.withPrefix(prefix: String): GetOption.Builder = withPrefix(prefix.asByteSequence)

private fun Client.getSingleChild(
  keyName: String,
  target: GetOption.SortTarget,
  order: SortOrder,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): GetResponse {
  val trailingKey = keyName.ensureSuffix("/")
  val getOption =
    getOption {
      isPrefix(true)
      withSortField(target)
      withSortOrder(order)
      withLimit(1)
    }
  return getResponse(trailingKey, getOption, rpc)
}

@JvmOverloads
fun Client.getChildrenKeys(
  keyName: String,
  target: GetOption.SortTarget = GetOption.SortTarget.KEY,
  order: SortOrder = SortOrder.ASCEND,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<String> = getChildren(keyName, target, order, true, rpc).keys

@JvmOverloads
fun Client.getChildrenValues(
  keyName: String,
  target: GetOption.SortTarget = GetOption.SortTarget.KEY,
  order: SortOrder = SortOrder.ASCEND,
): List<ByteSequence> = getChildren(keyName, target, order).values

@JvmOverloads
fun Client.getChildCount(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): Long {
  val trailingKey = keyName.ensureSuffix("/")
  val getOption =
    getOption {
      isPrefix(true)
      withCountOnly(true)
    }
  return getResponse(trailingKey, getOption, rpc).count
}

// Delete children keys
@JvmOverloads
fun Client.deleteChildren(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): List<String> {
  val trailingKey = keyName.ensureSuffix("/")
  val deleteOption =
    deleteOption {
      isPrefix(true)
      withPrevKV(true)
    }
  return retryRpc(rpc, "deleteChildren($keyName)") { kvClient.delete(trailingKey.asByteSequence, deleteOption) }
    .prevKvs.map { it.key.asString }
}
