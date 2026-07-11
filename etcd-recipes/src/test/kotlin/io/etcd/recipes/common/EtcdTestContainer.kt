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

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal object EtcdTestContainer {
  private const val ETCD_IMAGE = "gcr.io/etcd-development/etcd:v3.5.17"
  private const val CLIENT_PORT = 2379

  // The client port is bound to a FIXED host port chosen once per JVM: Docker does
  // NOT preserve randomly-assigned host ports across `docker restart` (verified:
  // the port changes), and the fault tests restart the container while jetcd
  // clients keep dialing the original endpoint. A fixed binding lives in the
  // container's HostConfig and survives restarts. Probing a free port and then
  // binding it is racy in principle; with one container per forked test JVM the
  // window is negligible.
  private val fixedClientHostPort: Int by lazy {
    ServerSocket(0).use { it.localPort }
  }

  // Lazy: the container is only started when a test in this JVM actually
  // asks for the endpoint. With forkEvery=1 each test class is its own JVM,
  // so this gives one container per test class. Testcontainers' Ryuk reaper
  // handles cleanup; the explicit shutdown hook is belt-and-suspenders that
  // stops the container promptly when the test JVM exits.
  private val container: GenericContainer<*> by lazy {
    // Only the client port is exposed: the peer port is unused on a single node, and
    // the fixed-binding modifier below replaces ALL host-port bindings — an exposed
    // port without a binding would stall Testcontainers' startup port check.
    val c = GenericContainer(DockerImageName.parse(ETCD_IMAGE))
      .withExposedPorts(CLIENT_PORT)
      .withCreateContainerCmdModifier { cmd ->
        cmd.hostConfig?.withPortBindings(
          PortBinding(Ports.Binding.bindPort(fixedClientHostPort), ExposedPort.tcp(CLIENT_PORT)),
        )
      }
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

  fun endpoint(): String = "http://${container.host}:$fixedClientHostPort"

  // ---- Fault-injection controls (used by io.etcd.recipes.fault tests) ----
  // These mutate the private container; they are only meaningful under
  // -PuseTestcontainers, where each forked test JVM owns its own etcd node.

  /** Freezes the etcd process (simulates a network partition / unresponsive server). */
  fun pause() {
    container.dockerClient.pauseContainerCmd(container.containerId).exec()
  }

  /** Unfreezes a [pause]d etcd process. */
  fun unpause() {
    container.dockerClient.unpauseContainerCmd(container.containerId).exec()
  }

  /**
   * Restarts the etcd process (simulates an etcd crash/restart). Docker preserves the
   * container's published host-port binding across a restart, which keeps [endpoint]
   * valid — asserted here so a Docker behavior change fails loudly instead of leaving
   * clients dialing a dead port.
   */
  fun restart() {
    val portBefore = container.getMappedPort(CLIENT_PORT)
    container.dockerClient.restartContainerCmd(container.containerId).exec()
    awaitReady()
    val portAfter = currentMappedClientPort()
    check(portBefore == portAfter) {
      "etcd container's mapped client port changed across restart ($portBefore -> $portAfter); " +
        "fault tests require a stable endpoint"
    }
  }

  /** Blocks until etcd answers a real KV request at [endpoint], or fails after [timeout]. */
  fun awaitReady(timeout: Duration = 30.seconds) {
    val start = TimeSource.Monotonic.markNow()
    var lastFailure: Exception? = null
    while (start.elapsedNow() < timeout) {
      try {
        connectToEtcd(listOf(endpoint())) { client ->
          client.kvClient.get("/fault/ready-probe".asByteSequence).get(2, TimeUnit.SECONDS)
        }
        return
      } catch (e: Exception) {
        lastFailure = e
        Thread.sleep(250)
      }
    }
    throw IllegalStateException("etcd container did not become ready within $timeout", lastFailure)
  }

  // Re-reads the port mapping from Docker rather than trusting any cached container info.
  private fun currentMappedClientPort(): Int {
    val info = container.dockerClient.inspectContainerCmd(container.containerId).exec()
    val binding = info.networkSettings.ports.bindings[ExposedPort.tcp(CLIENT_PORT)]
    return binding?.firstOrNull()?.hostPortSpec?.toInt()
      ?: error("no host binding found for etcd client port $CLIENT_PORT")
  }
}
