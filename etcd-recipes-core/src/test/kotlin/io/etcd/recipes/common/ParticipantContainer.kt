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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction", "MatchingDeclarationName")

package io.etcd.recipes.common

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration

internal object Participant {
  private const val RUNNER_IMAGE = "eclipse-temurin:17-jre"
  private const val JAR_DEST = "/app/runner.jar"
  private const val JAR_PROPERTY = "etcd.recipes.testRunnersJar"

  private val runnerJarPath: String by lazy {
    val path =
      System.getProperty(JAR_PROPERTY)
        ?: error(
          "System property $JAR_PROPERTY is not set. Run tests via Gradle with -PuseTestcontainers, " +
            "which wires the path through the test task configuration.",
        )
    require(File(path).isFile) { "$JAR_PROPERTY=$path does not point to a file" }
    path
  }

  /**
   * Build a one-shot participant container that runs `java -jar runner.jar <args>` and exits.
   * Caller is expected to start it. Use [io.etcd.recipes.common.awaitResults] (which polls etcd
   * for the runner-written result keys) rather than polling Docker for the container's exit
   * status — runners write their outcome to etcd as the last step before exiting, so a result
   * key appearing is a reliable completion signal.
   *
   * The startup wait strategy looks for the "Starting runner" log line emitted by Main.kt;
   * if the JAR fails to start, [GenericContainer.start] will time out.
   */
  fun newContainer(
    recipe: String,
    role: String,
    testId: String,
    participantId: String,
    extraArgs: Map<String, String> = emptyMap(),
    startupTimeout: Duration = Duration.ofSeconds(60),
  ): GenericContainer<*> {
    val args: MutableList<String> =
      [
        "--recipe=$recipe",
        "--role=$role",
        "--etcd=${EtcdContainerNetwork.inNetworkEndpoint()}",
        "--test-id=$testId",
        "--participant-id=$participantId",
      ]
    extraArgs.forEach { (k, v) -> args += "--$k=$v" }

    return GenericContainer(DockerImageName.parse(RUNNER_IMAGE))
      .withNetwork(EtcdContainerNetwork.network)
      .withCopyFileToContainer(MountableFile.forHostPath(runnerJarPath), JAR_DEST)
      .withCommand(*(["java", "-jar", JAR_DEST] + args).toTypedArray())
      .waitingFor(Wait.forLogMessage(".*Starting runner.*\\n", 1).withStartupTimeout(startupTimeout))
  }
}
