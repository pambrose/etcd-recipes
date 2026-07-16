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

@file:JvmName("LockUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.lock.LockResponse
import io.etcd.jetcd.lock.UnlockResponse

/**
 * Raw pass-through to etcd's lock service; prefer [io.etcd.recipes.lock.DistributedMutex].
 *
 * The default rpc config is [RpcResilience.DISABLED] — unlike every other
 * extension — because a lock call legitimately WAITS server-side for the current
 * holder; a 30s operation timeout would abort valid waits. Pass a bounded config
 * only when a bounded wait is what you mean. (jetcd already retries this RPC
 * internally on safe-redo failures, idempotently per lease.)
 */
@JvmOverloads
fun Client.lock(
  keyName: String,
  leaseId: Long,
  rpc: RpcResilience = RpcResilience.DISABLED,
): LockResponse = awaitRpc(rpc, "lock($keyName)", lockClient.lock(keyName.asByteSequence, leaseId))

@JvmOverloads
fun Client.unlock(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): UnlockResponse = retryRpc(rpc, "unlock($keyName)") { lockClient.unlock(keyName.asByteSequence) }
