/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.examples.discovery

import com.github.pambrose.common.util.sleep
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.discovery.withServiceDiscovery
import kotlin.time.Duration.Companion.days

fun main() {
  val urls = listOf("http://localhost:2379")
  val servicePath = "/services/test"

  connectToEtcd(urls) { client ->
    withServiceDiscovery(client, servicePath) {
      withServiceCache("TestName") {
        addListenerForChanges { eventType,
                                isAdd,
                                serviceName,
                                serviceInstance ->
          println("Change $isAdd $eventType $serviceName $serviceInstance")
          serviceInstance?.let {
            println("Payload: ${IntPayload.toObject(it.jsonPayload)}")
          }
          println("Instances: $instances")
        }

        start()
        sleep(1.days)
      }
    }
  }
}