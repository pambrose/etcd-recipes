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

package io.etcd.recipes.runners

import io.etcd.jetcd.Client
import io.etcd.recipes.common.connectToEtcd
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

interface RecipeRunner {
  val recipe: String
  val role: String

  fun run(
    client: Client,
    testId: String,
    participantId: String,
    args: Args,
  ): ParticipantResult
}

internal inline fun <reified P> RecipeRunner.result(
  testId: String,
  participantId: String,
  success: Boolean,
  payload: P,
): ParticipantResult =
  ParticipantResult(
    testId = testId,
    participantId = participantId,
    role = "$recipe/$role",
    success = success,
    payloadJson = encodePayload(payload),
  )

private val runners: Map<Pair<String, String>, RecipeRunner> =
  [
    BarrierWaiterRunner,
    ElectionParticipantRunner,
    QueueConsumerRunner,
    CounterIncrementRunner,
    ServiceRegistrationRunner,
  ].associateBy { it.recipe to it.role }

fun main(rawArgs: Array<String>) {
  val args = Args(rawArgs)
  val recipe = args.require("recipe")
  val role = args.require("role")
  val etcd = args.require("etcd")
  val testId = args.require("test-id")
  val participantId = args.require("participant-id")

  val runner =
    runners[recipe to role]
      ?: error("Unknown recipe/role combination: $recipe/$role")

  logger.info { "Starting runner recipe=$recipe role=$role testId=$testId participantId=$participantId" }

  @Suppress("TooGenericExceptionCaught")
  val result =
    try {
      connectToEtcd([etcd]) { client ->
        val outcome = runner.run(client, testId, participantId, args)
        client.recordResult(outcome)
        outcome
      }
    } catch (t: Throwable) {
      logger.error(t) { "Runner failed" }
      val failure =
        ParticipantResult(
          testId = testId,
          participantId = participantId,
          role = "$recipe/$role",
          success = false,
          errorMessage = "${t.javaClass.simpleName}: ${t.message}",
        )
      runCatching {
        connectToEtcd([etcd]) { client -> client.recordResult(failure) }
      }
      failure
    }

  exitProcess(if (result.success) 0 else 1)
}
