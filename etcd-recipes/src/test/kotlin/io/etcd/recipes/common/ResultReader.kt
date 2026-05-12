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

import io.etcd.jetcd.Client
import io.etcd.recipes.runners.ParticipantResult
import io.etcd.recipes.runners.RESULTS_PREFIX
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val json = Json { ignoreUnknownKeys = true }

private fun Client.readResults(testId: String): List<ParticipantResult> =
  getChildren("$RESULTS_PREFIX/$testId")
    .map { (_, value) -> json.decodeFromString(ParticipantResult.serializer(), value.asString) }

/**
 * Poll the result prefix until [expectedCount] participant results are present or [timeout]
 * elapses. Returns the parsed results keyed by participant id. Throws on timeout — the caller
 * should treat that as a test failure.
 */
fun Client.awaitResults(
  testId: String,
  expectedCount: Int,
  timeout: Duration,
  poll: Duration = 200.milliseconds,
): Map<String, ParticipantResult> {
  val deadline = System.nanoTime() + timeout.inWholeNanoseconds
  while (true) {
    val results = readResults(testId)
    if (results.size >= expectedCount) {
      return results.associateBy { it.participantId }
    }
    check(System.nanoTime() < deadline) {
      "Only ${results.size}/$expectedCount results arrived within $timeout for testId=$testId. " +
        "Got: ${results.map { it.participantId }}"
    }
    Thread.sleep(poll.inWholeMilliseconds)
  }
}

inline fun <reified T> ParticipantResult.payload(): T {
  val payload = checkNotNull(payloadJson) { "No payload on result for participant $participantId" }
  return Json.decodeFromString<T>(payload)
}
