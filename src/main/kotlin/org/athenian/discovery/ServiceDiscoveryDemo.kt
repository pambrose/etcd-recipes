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
import kotlin.time.seconds


fun main() {

    val url = "http://localhost:2379"
    val serviceName = "/services/test"

    ServiceDiscovery(url, serviceName).use { sd ->

        sd.start()

        val payload = IntPayload(-999)
        val service = ServiceInstance("TestName", payload.toJson())

        println(service.toJson())

        println("Registering")
        sd.registerService(service)
        println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
        println("Retrieved values: ${sd.queryForInstances(service.name)}")
        println("Retrieved names: ${sd.queryForNames()}")


        sleep(2.seconds)
        println("Updating")
        payload.intval = -888
        service.jsonPayload = payload.toJson()
        sd.updateService(service)
        println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
        println("Retrieved values: ${sd.queryForInstances(service.name)}")
        println("Retrieved names: ${sd.queryForNames()}")

        sleep(2.seconds)
        println("Unregistering")
        sd.unregisterService(service)
        sleep(3.seconds)

        println("Retrieved value: ${sd.queryForInstance(service.name, service.id)}")
        sleep(2.seconds)
    }
}