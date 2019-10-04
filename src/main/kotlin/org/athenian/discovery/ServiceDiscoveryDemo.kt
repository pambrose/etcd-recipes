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

import com.sudothought.common.util.sleep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.seconds


fun main() {

    @Serializable
    data class Test(val intval: Int) {
        fun toJson() = Json.stringify(serializer(), this)
    }

    val url = "http://localhost:2379"
    val serviceName = "/services/test"

    ServiceDiscovery<Int>(url, serviceName).use { sd ->

        sd.start()

        val payload = Test(-999)
        val si = ServiceInstance("A Name", payload.toJson())

        println(si.toJson())

        println("Registering")
        sd.registerService(si)
        sleep(2.seconds)
        println("Updating")
        sd.updateService(si)
        sleep(2.seconds)
        println("Unregistering")
        sd.unregisterService(si)
        sleep(2.seconds)

        println("Final sleep")
        sleep(5.seconds)

    }
}