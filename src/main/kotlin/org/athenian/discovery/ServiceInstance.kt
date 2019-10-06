/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.athenian.discovery

import com.sudothought.common.util.randomId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class ServiceInstance(val name: String,
                           var jsonPayload: String,
                           var address: String = "",
                           var port: Int = -1,
                           var sslPort: Int = -1,
                           var registrationTimeUTC: Long = Instant.now().toEpochMilli(),
                           var serviceType: ServiceType = ServiceType.DYNAMIC,
                           var uri: String = "",
                           var enabled: Boolean = true) {

    val id: String = randomId(9)

    init {
        require(name.isNotEmpty()) { "Name cannot be empty" }
    }

    fun toJson() = Json.stringify(serializer(), this)

    companion object {
        fun toObject(json: String) = Json.parse(serializer(), json)

        class ServiceInstanceBuilder(val name: String, val jsonPayload: String) {
            var address: String = ""
            var port: Int = -1
            var sslPort: Int = -1
            var registrationTimeUTC: Long = Instant.now().toEpochMilli()
            var serviceType: ServiceType = ServiceType.DYNAMIC
            var uri: String = ""
            var enabled: Boolean = true

            fun build(): ServiceInstance =
                ServiceInstance(name,
                                jsonPayload,
                                address,
                                port,
                                sslPort,
                                registrationTimeUTC,
                                serviceType,
                                uri,
                                enabled)
        }

        fun newBuilder(name: String, jsonPayload: String) = ServiceInstanceBuilder(name, jsonPayload)
    }
}