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
import io.etcd.recipes.common.putValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val RESULTS_PREFIX = "/test-results"

fun resultKey(
  testId: String,
  participantId: String,
): String = "$RESULTS_PREFIX/$testId/$participantId"

@Serializable
data class ParticipantResult(
  val testId: String,
  val participantId: String,
  val role: String,
  val success: Boolean,
  val errorMessage: String? = null,
  // Recipe-specific payload encoded as JSON so the orchestrator can decode against the
  // recipe-specific data class it expects. Avoids a sealed-class hierarchy that would force
  // every consumer to know every recipe shape.
  val payloadJson: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

fun Client.recordResult(result: ParticipantResult) {
  putValue(resultKey(result.testId, result.participantId), json.encodeToString(ParticipantResult.serializer(), result))
}

inline fun <reified T> encodePayload(value: T): String = Json.encodeToString(value)
