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

package io.etcd.recipes.queue

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction

// Random suffix length for queue item keys. 62^16 ≈ 4.8e28 values, so two items created
// in the same millisecond practically never draw the same key (3 characters gave only
// 238,328); the create-only writes below make a collision harmless anyway.
internal const val ITEM_KEY_SUFFIX_LENGTH = 16

// A draw that keeps colliding means the key source is broken, not unlucky.
private const val MAX_KEY_ATTEMPTS = 10

// Writes [value] under a key from [newKey], but only if that key is absent, drawing a
// fresh key when it is taken, so an enqueue can never overwrite a queued item. Each
// attempt is one transaction, and transactions are never retried: blindly re-sending a
// write whose outcome is unknown could re-create an item a consumer has already taken.
internal fun Client.createUniqueKey(
  value: ByteSequence,
  rpc: RpcResilience,
  newKey: () -> String,
): String = createUniqueKeys(listOf(value), rpc) { listOf(newKey()) }.single()

// The all-or-nothing form for batches: every key is written, or none is and all are
// redrawn.
internal fun Client.createUniqueKeys(
  values: List<ByteSequence>,
  rpc: RpcResilience,
  newKeys: () -> List<String>,
): List<String> {
  repeat(MAX_KEY_ATTEMPTS) {
    val keys = newKeys()
    check(keys.size == values.size) { "Expected ${values.size} keys, got ${keys.size}" }
    val created =
      transaction(rpc) {
        If(*keys.map { it.doesNotExist }.toTypedArray())
        Then(*keys.zip(values) { key, value -> key setTo value }.toTypedArray())
      }.isSucceeded
    if (created) return keys
  }
  throw EtcdRecipeRuntimeException("No unique key after $MAX_KEY_ATTEMPTS attempts")
}
