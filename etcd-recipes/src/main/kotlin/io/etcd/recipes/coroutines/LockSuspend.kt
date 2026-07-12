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

import io.etcd.jetcd.Client
import io.etcd.jetcd.lock.LockResponse
import io.etcd.jetcd.lock.UnlockResponse
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.asByteSequence

/**
 * Suspending twin of the raw `lock` pass-through; prefer
 * [io.etcd.recipes.lock.DistributedMutex]. The default rpc config is
 * [RpcResilience.DISABLED] — unlike every other suspend twin — because a lock call
 * legitimately WAITS server-side for the current holder; a 30s operation timeout
 * would abort valid waits. Pass a bounded config only when a bounded wait is what
 * you mean.
 */
suspend fun Client.awaitLock(
  keyName: String,
  leaseId: Long,
  rpc: RpcResilience = RpcResilience.DISABLED,
): LockResponse = suspendAwaitRpc(rpc, "lock($keyName)", lockClient.lock(keyName.asByteSequence, leaseId))

/** Suspending twin of the raw `unlock` pass-through. */
suspend fun Client.awaitUnlock(
  keyName: String,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): UnlockResponse = suspendRetryRpc(rpc, "unlock($keyName)") { lockClient.unlock(keyName.asByteSequence) }
