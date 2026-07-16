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

@file:JvmName("TypedServiceInstances")

package io.etcd.recipes.discovery

import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.discovery.ServiceInstance.Companion.ServiceInstanceBuilder

// The typed payload layers over the opaque `jsonPayload` String, so the ServiceInstance wire format
// (the whole-instance JSON produced by toJson()) is byte-for-byte unchanged. Because jsonPayload is
// a String, these require a UTF-8 text codec (StringCodec, jsonCodec, the JSON JacksonCodec) — a
// binary codec is unsupported here.

/** Decodes this instance's opaque [ServiceInstance.jsonPayload] through [codec]. */
fun <T> ServiceInstance.payload(codec: EtcdCodec<T>): T = codec.decode(jsonPayload.asByteSequence)

/** Encodes [value] into this instance's [ServiceInstance.jsonPayload] through [codec], in place. */
fun <T> ServiceInstance.setPayload(
  value: T,
  codec: EtcdCodec<T>,
) {
  jsonPayload = codec.encode(value).asString
}

/** Builds a [ServiceInstance] whose `jsonPayload` is [payload] encoded through [codec]. */
@JvmOverloads
fun <T> serviceInstance(
  name: String,
  payload: T,
  codec: EtcdCodec<T>,
  initReceiver: ServiceInstanceBuilder.() -> ServiceInstanceBuilder = { this },
): ServiceInstance = ServiceInstance.newBuilder(name, codec.encode(payload).asString).initReceiver().build()
