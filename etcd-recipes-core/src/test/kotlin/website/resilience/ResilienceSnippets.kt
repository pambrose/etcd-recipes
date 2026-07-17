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

package website.resilience

import io.etcd.jetcd.Client
import io.etcd.recipes.cache.PathChildrenCache
import io.etcd.recipes.common.LeaseResilience
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.RetryPolicy
import io.etcd.recipes.common.RpcResilience
import io.etcd.recipes.common.WatchRecoveryEvent
import io.etcd.recipes.common.WatchResilience
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

fun defaultResilience(client: Client) {
  // --8<-- [start:defaults]
  // Every recipe ends with a `resilience: ResilienceConfig = ResilienceConfig.DEFAULT`
  // parameter, so RPC retries, watch recovery, and lease healing are all on already.
  PathChildrenCache(client, "/cache/orders").use { cache ->
    cache.start(true)
    logger.info { "Resilient without asking: ${cache.currentDataAsMap.size} children" }
  }
  // --8<-- [end:defaults]
}

fun customResilience(client: Client) {
  // --8<-- [start:custom-config]
  val resilience =
    ResilienceConfig(
      // Re-subscribe a dead watch fast, then back off to a 5s ceiling.
      watch = WatchResilience(RetryPolicy.exponentialBackoff(initialDelay = 100.milliseconds, maxDelay = 5.seconds)),
      // Never stop trying to heal a lease: the key it owns must come back.
      lease = LeaseResilience(RetryPolicy.forever),
      // Bound each RPC at 10s and give up after 8 attempts.
      rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 8), operationTimeout = 10.seconds),
    )

  PathChildrenCache(client, "/cache/orders", resilience = resilience).use { cache ->
    cache.start(true)
  }
  // --8<-- [end:custom-config]
}

fun disabledResilience(client: Client) {
  // --8<-- [start:disabled]
  // The pre-0.12 behavior, should you want it back: no RPC deadline, no RPC retry,
  // a fatally dead watcher stays dead, an expired lease is gone for good.
  PathChildrenCache(client, "/cache/orders", resilience = ResilienceConfig.DISABLED).use { cache ->
    cache.start(true)
  }
  // --8<-- [end:disabled]
}

fun retryPolicies() {
  // --8<-- [start:retry-policy]
  // Exponential backoff with jitter. Every argument has a default; this spells them
  // all out, and adds a 2-minute ceiling on the total recovery window.
  val paced =
    RetryPolicy.exponentialBackoff(
      initialDelay = 250.milliseconds,
      maxDelay = 15.seconds,
      factor = 2.0,
      jitterRatio = 0.25,
      maxAttempts = Int.MAX_VALUE,
      maxElapsed = 2.minutes,
    )

  // A fixed delay for a fixed number of attempts — the RPC default.
  val quick = RetryPolicy.bounded(maxAttempts = 4, delay = 250.milliseconds)

  // The two constants: unbounded default backoff, and "never recover at all".
  logger.info { "$paced / $quick / ${RetryPolicy.forever} / ${RetryPolicy.never}" }
  // --8<-- [end:retry-policy]
}

fun customRetryPolicy() {
  // --8<-- [start:custom-retry-policy]
  // RetryPolicy is a fun interface, so a lambda is a policy. Return null to give up.
  val policy =
    RetryPolicy { attempt, elapsed ->
      when {
        // A partition this long is an incident, not a blip: stop and let the
        // Failed/Expired event escalate instead of retrying into the void.
        elapsed > 1.minutes -> null

        // Hammer briefly for an etcd restart, which is usually over in a second.
        attempt <= 3 -> 200.milliseconds

        else -> 5.seconds
      }
    }

  logger.info { "First delay: ${policy.nextDelay(attempt = 1, elapsed = 0.seconds)}" }
  // --8<-- [end:custom-retry-policy]
}

fun perCallResilience(client: Client) {
  // --8<-- [start:per-call-rpc]
  // The extension layer takes an RpcResilience per call, so a latency-sensitive read
  // can opt out of retrying and use a tighter deadline than the recipe's default.
  val oneShot = RpcResilience(RetryPolicy.never, operationTimeout = 2.seconds)
  logger.info { "flag=${client.getValue("/config/flag", "off", oneShot)}" }
  // --8<-- [end:per-call-rpc]
}

fun watchRecovery(client: Client) {
  // --8<-- [start:watch-recovery]
  client.watcher(
    keyName = "/config",
    option = watchOption { isPrefix(true) },
    resilience = WatchResilience.DEFAULT,
    recoveryListener = { event -> onRecovery(event) },
    // Called only after a compaction, before re-subscribing: re-read the world,
    // rebuild whatever state was derived from the lost events, and return the
    // revision to resume from.
    resyncWith = {
      val response = client.getResponse("/config", getOption { isPrefix(true) })
      logger.info { "Resynced ${response.kvs.size} keys after compaction" }
      response.header.revision + 1
    },
  ) { response ->
    logger.info { "${response.events.size} event(s)" }
  }.use { watcher ->
    logger.info { "Watching; closed=${watcher.isClosed}" }
  }
  // --8<-- [end:watch-recovery]
}

// --8<-- [start:recovery-events]
fun onRecovery(event: WatchRecoveryEvent) {
  when (event) {
    // The stream errored; recovery is under way. Derived state is stale from here.
    is WatchRecoveryEvent.Suspended -> logger.warn(event.cause) { "Watch suspended on ${event.watchedKey}" }

    // Back, resuming just past the last event seen: nothing lost, nothing duplicated.
    is WatchRecoveryEvent.Resubscribed -> logger.info { "Resumed at revision ${event.resumeRevision}" }

    // Back, but etcd had compacted the resume revision away: events between
    // compactRevision and anchorRevision are gone for good.
    is WatchRecoveryEvent.Resynced -> logger.warn { "Gap from ${event.compactRevision} to ${event.anchorRevision}" }

    // The retry policy gave up. This watcher is dead and will not come back.
    is WatchRecoveryEvent.Failed -> logger.error(event.cause) { "Watch abandoned on ${event.watchedKey}" }
  }
}
// --8<-- [end:recovery-events]

fun scopedFunctionWorkaround(client: Client) {
  // --8<-- [start:scoped-workaround]
  val resilience = ResilienceConfig(rpc = RpcResilience(RetryPolicy.bounded(maxAttempts = 8)))

  // withPathChildrenCache(...) has no `resilience` parameter, so construct the
  // recipe directly and use Kotlin's own use { } instead.
  PathChildrenCache(client, "/cache/orders", resilience = resilience).use { cache ->
    cache.start(true)
    logger.info { "${cache.currentDataAsMap.size} children" }
  }
  // --8<-- [end:scoped-workaround]
}
