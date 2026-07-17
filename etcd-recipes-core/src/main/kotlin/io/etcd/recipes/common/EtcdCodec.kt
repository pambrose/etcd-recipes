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

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Converts a typed value to and from the raw [ByteSequence] etcd stores. Recipes that traffic in
 * a typed payload (starting with [io.etcd.recipes.cache.NodeCache]) take an [EtcdCodec] so callers
 * choose their own wire format instead of hand-marshalling. Built-ins: [ByteSequenceCodec],
 * [StringCodec], and [KotlinxJsonCodec] (via [jsonCodec]).
 */
interface EtcdCodec<T> {
  fun encode(value: T): ByteSequence

  fun decode(bytes: ByteSequence): T
}

/** The identity codec: the payload is already a [ByteSequence]. */
object ByteSequenceCodec : EtcdCodec<ByteSequence> {
  override fun encode(value: ByteSequence): ByteSequence = value

  override fun decode(bytes: ByteSequence): ByteSequence = bytes
}

/** UTF-8 string payloads. */
object StringCodec : EtcdCodec<String> {
  override fun encode(value: String): ByteSequence = value.asByteSequence

  override fun decode(bytes: ByteSequence): String = bytes.asString
}

/**
 * `kotlinx-serialization` JSON payloads. Prefer the reified [jsonCodec] factory; construct this
 * directly only when you already hold a [serializer] or a customized [Json].
 */
class KotlinxJsonCodec<T>(
  private val serializer: KSerializer<T>,
  private val json: Json = Json,
) : EtcdCodec<T> {
  override fun encode(value: T): ByteSequence = json.encodeToString(serializer, value).asByteSequence

  override fun decode(bytes: ByteSequence): T = json.decodeFromString(serializer, bytes.asString)
}

/** A [KotlinxJsonCodec] for any `@Serializable` [T], resolving its serializer at the call site. */
inline fun <reified T> jsonCodec(json: Json = Json): EtcdCodec<T> = KotlinxJsonCodec(serializer(), json)
