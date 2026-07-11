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

package io.etcd.recipes.common

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

internal object EtcdTestContainer {
  private const val ETCD_IMAGE = "gcr.io/etcd-development/etcd:v3.5.17"
  private const val CLIENT_PORT = 2379
  private const val PEER_PORT = 2380

  // Lazy: the container is only started when a test in this JVM actually
  // asks for the endpoint. With forkEvery=1 each test class is its own JVM,
  // so this gives one container per test class. Testcontainers' Ryuk reaper
  // handles cleanup; the explicit shutdown hook is belt-and-suspenders that
  // stops the container promptly when the test JVM exits.
  private val container: GenericContainer<*> by lazy {
    val c = GenericContainer(DockerImageName.parse(ETCD_IMAGE))
      .withExposedPorts(CLIENT_PORT, PEER_PORT)
      .withCommand(
        "etcd",
        "--listen-client-urls=http://0.0.0.0:$CLIENT_PORT",
        "--advertise-client-urls=http://0.0.0.0:$CLIENT_PORT",
      )
      .waitingFor(Wait.forLogMessage(".*ready to serve client requests.*\\n", 1))
    c.start()
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { c.stop() } })
    c
  }

  fun endpoint(): String = "http://${container.host}:${container.getMappedPort(CLIENT_PORT)}"
}
