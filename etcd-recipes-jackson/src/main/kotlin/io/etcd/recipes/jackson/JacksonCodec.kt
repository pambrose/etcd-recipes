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

package io.etcd.recipes.jackson

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.etcd.jetcd.ByteSequence
import io.etcd.recipes.common.EtcdCodec

/**
 * A Jackson-backed [EtcdCodec] that marshals values to and from JSON bytes. This is the Java-facing
 * counterpart to the library's built-in `KotlinxJsonCodec`; it lives in its own optional module so
 * only projects that want Jackson pull it in.
 *
 * Construct it with either a [Class] token (`new JacksonCodec<>(Config.class)`) or a
 * [TypeReference] for generic payloads (`new JacksonCodec<>(new TypeReference<List<Config>>() {})`).
 * Kotlin callers can use the reified [jacksonCodec] helper.
 *
 * The default [ObjectMapper] is a vanilla instance — suitable for Java beans and any type Jackson
 * can construct out of the box. To marshal Kotlin data classes (which have no no-arg constructor),
 * pass a mapper configured with `jackson-module-kotlin`.
 */
class JacksonCodec<T>
  private constructor(
    private val reader: (ByteArray) -> T,
    private val mapper: ObjectMapper,
  ) : EtcdCodec<T> {
    /** Marshals values of [type] with the given [mapper] (a vanilla [ObjectMapper] by default). */
    @JvmOverloads
    constructor(type: Class<T>, mapper: ObjectMapper = ObjectMapper()) : this({ mapper.readValue(it, type) }, mapper)

    /** Marshals a generic [type] (e.g. `List<Config>`) with the given [mapper]. */
    @JvmOverloads
    constructor(
      type: TypeReference<T>,
      mapper: ObjectMapper = ObjectMapper(),
    ) : this({ mapper.readValue(it, type) }, mapper)

    override fun encode(value: T): ByteSequence = ByteSequence.from(mapper.writeValueAsBytes(value))

    override fun decode(bytes: ByteSequence): T = reader(bytes.bytes)
  }

/** A [JacksonCodec] for any [T], resolving its type via a reified [TypeReference] at the call site. */
inline fun <reified T> jacksonCodec(mapper: ObjectMapper = ObjectMapper()): EtcdCodec<T> =
  JacksonCodec(object : TypeReference<T>() {}, mapper)
