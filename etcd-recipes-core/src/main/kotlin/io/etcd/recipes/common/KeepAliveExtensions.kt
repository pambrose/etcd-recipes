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

@file:JvmName("KeepAliveUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: String,
  ttlSecs: Long,
  block: () -> Unit,
) = putValueWithKeepAlive(keyName, keyval, ttlSecs.seconds, block)

fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: Int,
  ttlSecs: Long,
  block: () -> Unit,
) = putValueWithKeepAlive(keyName, keyval, ttlSecs.seconds, block)

fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: Long,
  ttlSecs: Long,
  block: () -> Unit,
) = putValueWithKeepAlive(keyName, keyval, ttlSecs.seconds, block)

fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: ByteSequence,
  ttlSecs: Long,
  block: () -> Unit,
) = putValuesWithKeepAlive([keyName to keyval], ttlSecs, block = block)

@JvmName("putValueWithKeepAliveDur")
fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: String,
  ttl: Duration,
  block: () -> Unit,
) = putValueWithKeepAlive(keyName, keyval.asByteSequence, ttl, block)

@JvmName("putValueWithKeepAliveDur")
fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: Int,
  ttl: Duration,
  block: () -> Unit,
) = putValueWithKeepAlive(keyName, keyval.asByteSequence, ttl, block)

@JvmName("putValueWithKeepAliveDur")
fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: Long,
  ttl: Duration,
  block: () -> Unit,
) = putValueWithKeepAlive(keyName, keyval.asByteSequence, ttl, block)

@JvmName("putValueWithKeepAliveDur")
fun Client.putValueWithKeepAlive(
  keyName: String,
  keyval: ByteSequence,
  ttl: Duration,
  block: () -> Unit,
) = putValuesWithKeepAlive([keyName to keyval], ttl, block = block)

@JvmOverloads
fun Client.putValuesWithKeepAlive(
  kvs: Collection<Pair<String, ByteSequence>>,
  ttlSecs: Long,
  onKeepAliveError: (Throwable) -> Unit = {},
  rpc: RpcResilience = RpcResilience.DEFAULT,
  block: () -> Unit,
) = putValuesWithKeepAlive(kvs, ttlSecs.seconds, onKeepAliveError, rpc, block)

@JvmOverloads
@Suppress("TooGenericExceptionCaught")
fun Client.putValuesWithKeepAlive(
  kvs: Collection<Pair<String, ByteSequence>>,
  ttl: Duration,
  onKeepAliveError: (Throwable) -> Unit = {},
  rpc: RpcResilience = RpcResilience.DEFAULT,
  block: () -> Unit,
) {
  val lease = leaseGrant(ttl, rpc)
  // Revoke the lease however this ends — a failed put, a keep-alive that can't start, or
  // the block returning or throwing — so the keys go with the block rather than lingering
  // until the TTL runs out.
  try {
    // One transaction, so a reader never sees half of a multi-key registration
    transaction(rpc) {
      Then(*kvs.map { (key, value) -> key.setTo(value, putOption { withLeaseId(lease.id) }) }.toTypedArray())
    }
    // onKeepAliveError lets a holder observe the keep-alive dying after setup
    // succeeds (renewal stream errors or stops) rather than only on close().
    keepAliveWith(lease, onKeepAliveError) { block() }
  } finally {
    leaseRevoke(lease, rpc)
  }
}
