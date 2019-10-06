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

    ServiceDiscovery<Int>(url, serviceName).use { sd ->

        sd.start()

        val test = ServicePayload(-999)
        val si = ServiceInstance("TestName", test.toJson())

        println(si.toJson())

        println("Registering")
        sd.registerService(si)
        println("Retrieved value: ${sd.queryForInstance(si.name, si.id)}")
        println("Retrieved values: ${sd.queryForInstances(si.name)}")
        println("Retrieved names: ${sd.queryForNames()}")


        sleep(2.seconds)
        println("Updating")
        test.intval = -888
        si.jsonPayload = test.toJson()
        sd.updateService(si)
        println("Retrieved value: ${sd.queryForInstance(si.name, si.id)}")
        println("Retrieved values: ${sd.queryForInstances(si.name)}")
        println("Retrieved names: ${sd.queryForNames()}")

        sleep(2.seconds)
        println("Unregistering")
        sd.unregisterService(si)

        sleep(3.seconds)

        try {
            println("Retrieved value: ${sd.queryForInstance(si.name, si.id)}")
        } catch (e: Exception) {
            println("Had exception $e")
        }

        sleep(2.seconds)

        println("Final sleep")
        sleep(2.seconds)
    }
}