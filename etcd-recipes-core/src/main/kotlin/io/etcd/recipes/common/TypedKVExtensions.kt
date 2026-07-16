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

@file:JvmName("TypedKVUtils")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.options.PutOption

/**
 * Writes [value] encoded through [codec], removing the hand-marshalling that the raw
 * `putValue(key, ByteSequence)` overload leaves to the caller. Composes the existing
 * [ByteSequence][io.etcd.jetcd.ByteSequence] put, so it inherits its retry semantics.
 *
 * `client.putValue("cfg", config, jsonCodec<Config>())`
 */
fun <T> Client.putValue(
  keyName: String,
  value: T,
  codec: EtcdCodec<T>,
  option: PutOption = PutOption.DEFAULT,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): PutResponse = putValue(keyName, codec.encode(value), option, rpc)

/**
 * Reads [keyName] and decodes it through [codec], or null when the key is absent. The counterpart
 * to the typed [putValue]. Use `?: default` for a fallback.
 *
 * `client.getValue("cfg", jsonCodec<Config>())`
 */
fun <T> Client.getValue(
  keyName: String,
  codec: EtcdCodec<T>,
  rpc: RpcResilience = RpcResilience.DEFAULT,
): T? = getValue(keyName, rpc)?.let(codec::decode)
