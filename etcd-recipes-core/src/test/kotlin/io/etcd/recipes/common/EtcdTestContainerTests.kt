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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The test fixture must never hand out an etcd endpoint that another process answers.
 *
 * It picks a host port, closes it, and asks Docker to bind it, so another process can
 * take the port first. IDEs and the Gradle daemon keep many listeners on `127.0.0.1`
 * ephemeral ports. When one of those holds the port, Docker Desktop on macOS still
 * publishes the container on `0.0.0.0`/`[::]` without an error. Java resolves
 * `localhost` to `127.0.0.1` first, so every client RPC then reaches the other process
 * and times out: a test class whose etcd "never answers". On Linux the same collision
 * is a start-time "address already in use" error, which the fixture did not retry.
 */
class EtcdTestContainerTests : StringSpec() {
  init {
    "a host port already taken on 127.0.0.1 is skipped, and the endpoint reaches etcd" {
      assumeTrue(System.getProperty("etcd.recipes.testcontainers") == "true", "needs -PuseTestcontainers")

      // A loopback-only listener, like the ones IDEs and the Gradle daemon keep
      ServerSocket().use { squatter ->
        squatter.reuseAddress = true
        squatter.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val takenPort = squatter.localPort
        val ports = ArrayDeque(listOf(takenPort, ServerSocket(0).use { it.localPort }))

        val started = EtcdTestContainer.startEtcd { ports.removeFirst() }
        try {
          withClue("the fixture started etcd on a port another process already holds") {
            started.hostPort shouldNotBe takenPort
          }
          connectToEtcd([started.endpoint]) { client ->
            client.kvClient.put("/fixture/probe".asByteSequence, "ok".asByteSequence).get(10, TimeUnit.SECONDS)
            client.kvClient.get("/fixture/probe".asByteSequence).get(10, TimeUnit.SECONDS)
              .kvs.single().value.asString shouldBe "ok"
          }
        } finally {
          started.container.stop()
        }
      }
    }
  }
}
