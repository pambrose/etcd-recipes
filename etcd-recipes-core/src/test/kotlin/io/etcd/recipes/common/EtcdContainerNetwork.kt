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
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Shared Testcontainers network plus a single etcd container reachable from
 * both the orchestrator JVM (via the host-mapped port) and from participant
 * containers (via the network alias [ETCD_ALIAS]). Coexists with
 * [EtcdTestContainer]; thread-based tests continue using that.
 */
internal object EtcdContainerNetwork {
  private const val ETCD_IMAGE = "gcr.io/etcd-development/etcd:v3.5.17"
  private const val CLIENT_PORT = 2379
  private const val PEER_PORT = 2380
  const val ETCD_ALIAS = "etcd"

  val network: Network by lazy {
    val n = Network.newNetwork()
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { n.close() } })
    n
  }

  private val container: GenericContainer<*> by lazy {
    val c =
      GenericContainer(DockerImageName.parse(ETCD_IMAGE))
        .withNetwork(network)
        .withNetworkAliases(ETCD_ALIAS)
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

  /** Endpoint reachable from the host JVM running the orchestrator test. */
  fun hostEndpoint(): String = "http://${container.host}:${container.getMappedPort(CLIENT_PORT)}"

  /** Endpoint reachable from another container attached to [network]. */
  fun inNetworkEndpoint(): String {
    // Force lazy init so the network and container exist before participants try to attach.
    container.containerId
    return "http://$ETCD_ALIAS:$CLIENT_PORT"
  }
}
