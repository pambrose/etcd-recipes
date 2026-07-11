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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.discovery

import com.pambrose.common.util.randomId
import io.etcd.recipes.common.EtcdConnector.Companion.TOKEN_LENGTH
import io.etcd.recipes.discovery.ServiceInstance.Companion.ServiceInstanceBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class ServiceInstance(
  val name: String,
  var jsonPayload: String,
  var address: String = "",
  var port: Int = -1,
  var sslPort: Int = -1,
  var registrationTimeUTC: Long = Instant.now().toEpochMilli(),
  var serviceType: ServiceType = ServiceType.DYNAMIC,
  var uri: String = "",
  var enabled: Boolean = true,
) {
  val id: String = randomId(TOKEN_LENGTH)

  init {
    require(name.isNotEmpty()) { "Name cannot be empty" }
  }

  fun toJson() = wireFormat.encodeToString(serializer(), this)

  companion object {
    // encodeDefaults: registrationTimeUTC's default is Instant.now(), so with the
    // stock configuration kotlinx omits the field whenever serialization runs in
    // the same clock millisecond as construction — and a later parse back-fills a
    // fresh (different) timestamp, corrupting the round-trip. Encoding defaults
    // makes the wire format stable regardless of serialization timing; readers of
    // the old format are unaffected (fields only become more complete).
    private val wireFormat = Json { encodeDefaults = true }

    @JvmStatic
    fun toObject(json: String) = wireFormat.decodeFromString(serializer(), json)

    class ServiceInstanceBuilder(
      val name: String,
      val jsonPayload: String,
    ) {
      var address: String = ""
      var port: Int = -1
      var sslPort: Int = -1
      var registrationTimeUTC: Long = Instant.now().toEpochMilli()
      var serviceType: ServiceType = ServiceType.DYNAMIC
      var uri: String = ""
      var enabled: Boolean = true

      fun build(): ServiceInstance =
        ServiceInstance(
          name,
          jsonPayload,
          address,
          port,
          sslPort,
          registrationTimeUTC,
          serviceType,
          uri,
          enabled,
        )
    }

    @JvmStatic
    fun newBuilder(
      name: String,
      jsonPayload: String,
    ) = ServiceInstanceBuilder(name, jsonPayload)
  }
}

@JvmOverloads
fun serviceInstance(
  name: String,
  jsonPayload: String,
  initReceiver: ServiceInstanceBuilder.() -> ServiceInstanceBuilder = { this },
): ServiceInstance = ServiceInstance.newBuilder(name, jsonPayload).initReceiver().build()
