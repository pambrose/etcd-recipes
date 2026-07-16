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

package io.etcd.recipes.keyvalue

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.LeaseListener
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.asString
import io.etcd.recipes.keyvalue.TransientKeyValue.Companion.defaultClientId
import java.io.Closeable
import java.util.concurrent.Executor

/** Scopes a [TypedTransientKeyValue] to [receiver], closing it on exit. */
@JvmOverloads
fun <T, R> withTypedTransientKeyValue(
  client: Client,
  keyPath: String,
  value: T,
  codec: EtcdCodec<T>,
  leaseTtlSecs: Long = EtcdConnector.DEFAULT_TTL_SECS,
  autoStart: Boolean = true,
  userExecutor: Executor? = null,
  clientId: String = defaultClientId(),
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  receiver: TypedTransientKeyValue<T>.() -> R,
): R =
  TypedTransientKeyValue(client, keyPath, value, codec, leaseTtlSecs, autoStart, userExecutor, clientId, resilience)
    .use { it.receiver() }

/**
 * A typed lease-backed key/value: a [TransientKeyValue] whose (immutable) [value] is encoded once
 * through [codec] and published under the lease. Because the published value is a `String`, this
 * requires a UTF-8 text codec (`StringCodec`, `jsonCodec`, the JSON `JacksonCodec`). Read it back
 * with the typed `getValue(key, codec)`. The full connector API stays reachable through [untyped].
 */
class TypedTransientKeyValue<T>
  @JvmOverloads
  constructor(
    client: Client,
    keyPath: String,
    value: T,
    codec: EtcdCodec<T>,
    leaseTtlSecs: Long = EtcdConnector.DEFAULT_TTL_SECS,
    autoStart: Boolean = true,
    userExecutor: Executor? = null,
    clientId: String = defaultClientId(),
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : Closeable {
    val untyped: TransientKeyValue =
      TransientKeyValue(
        client,
        keyPath,
        codec.encode(value).asString,
        leaseTtlSecs,
        autoStart,
        userExecutor,
        clientId,
        resilience,
      )

    /** Starts publishing (for `autoStart = false`); a no-op-safe delegate to the underlying recipe. */
    fun start(): TypedTransientKeyValue<T> {
      untyped.start()
      return this
    }

    fun addLeaseListener(listener: LeaseListener) = untyped.addLeaseListener(listener)

    fun removeLeaseListener(listener: LeaseListener) = untyped.removeLeaseListener(listener)

    override fun close() = untyped.close()
  }
