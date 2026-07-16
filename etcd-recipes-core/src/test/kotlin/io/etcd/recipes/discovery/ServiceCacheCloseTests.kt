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

package io.etcd.recipes.discovery

import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.urls
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Regression test for: ServiceCache.close() deadlocked when the cache received
// zero watch events between start() and close(), because startThreadComplete
// was only set inside the watcher callback.
//
// The test deliberately does NOT use withServiceDiscovery — when the bug is
// present, close() blocks while holding ServiceCache's @Synchronized lock,
// which would also stall ServiceDiscovery.close() during cleanup and prevent
// the test JVM from shutting down.
class ServiceCacheCloseTests : StringSpec() {
  init {
    "closeReturnsWhenNoWatchEventsReceived" {
      val path = "/discovery/ServiceCacheCloseTests-no-events"
      val namesPath = path.appendToPath("/names")
      val name = "NoEventsService"
      val closed = CountDownLatch(1)

      connectToEtcd(urls) { client ->
        val cache = ServiceCache(client, namesPath, name).start()

        // No services are registered — the watcher receives no events.
        // Bug: close() blocks forever on startThreadComplete.waitUntilTrue().
        // Use a daemon thread so the test JVM still exits when the bug is
        // present and close() never returns.
        thread(isDaemon = true, name = "service-cache-closer") {
          cache.close()
          closed.countDown()
        }

        closed.await(10, TimeUnit.SECONDS) shouldBe true
      }
    }

    // The fix moved startThreadComplete out of the watcher callback, so the
    // normal-path code (events DO arrive before close()) must still complete
    // promptly. Pin that behavior so a future revert that re-couples the
    // signal to the callback would fail here AND in the no-events test above.
    "closeReturnsAfterReceivingEvents" {
      val path = "/discovery/ServiceCacheCloseTests-with-events"
      val namesPath = path.appendToPath("/names")
      val name = "WithEventsService"
      val seenEvent = CountDownLatch(1)
      val closed = CountDownLatch(1)

      connectToEtcd(urls) { client ->
        client.deleteChildren(namesPath)

        val cache = ServiceCache(client, namesPath, name).start()
        cache.addListenerForChanges { eventType, _, _, _ ->
          if (eventType == PUT) seenEvent.countDown()
        }

        // PUT a real ServiceInstance under namesPath/name/<id> — toObject
        // deserialization runs inside the watcher callback, so the value
        // must be valid JSON.
        val instance = ServiceInstance(name, "{}")
        val key = namesPath.appendToPath(name).appendToPath(instance.id)
        client.transaction { Then(key.setTo(instance.toJson())) }

        seenEvent.await(10, TimeUnit.SECONDS) shouldBe true

        thread(isDaemon = true, name = "service-cache-closer") {
          cache.close()
          closed.countDown()
        }

        closed.await(10, TimeUnit.SECONDS) shouldBe true

        client.deleteChildren(namesPath)
      }
    }

    // close() guards on closeCalled and returns early; verify a second close()
    // returns promptly (no hang on startThreadComplete, no thrown exception).
    "closeIsIdempotent" {
      val path = "/discovery/ServiceCacheCloseTests-idempotent"
      val namesPath = path.appendToPath("/names")
      val name = "IdempotentService"
      val secondClosed = CountDownLatch(1)

      connectToEtcd(urls) { client ->
        val cache = ServiceCache(client, namesPath, name).start()

        cache.close()

        thread(isDaemon = true, name = "service-cache-second-closer") {
          cache.close()
          secondClosed.countDown()
        }

        secondClosed.await(5, TimeUnit.SECONDS) shouldBe true
      }
    }
  }
}
