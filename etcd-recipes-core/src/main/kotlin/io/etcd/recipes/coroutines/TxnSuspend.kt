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
import io.etcd.jetcd.Txn
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.recipes.common.RpcResilience

/**
 * Suspending twin of `transaction`: commits the transaction built by [receiver] with
 * the operation timeout applied but NEVER retried — a failed commit is ambiguous (it
 * may have been applied), and CAS retry decisions belong to the callers' own loops.
 */
suspend fun Client.awaitTransaction(
  rpc: RpcResilience = RpcResilience.DEFAULT,
  receiver: Txn.() -> Txn,
): TxnResponse =
  suspendAwaitRpc(
    rpc,
    "transaction",
    kvClient.txn().run {
      receiver()
      commit()
    },
  )
