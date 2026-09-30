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

package io.etcd.recipes.lock

import io.etcd.recipes.common.EtcdRecipes
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.pollUntil
import io.etcd.recipes.common.urls
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Each lock hands its holder a fencing token: a number etcd assigned to the hold, larger
 * than any earlier conflicting holder's, so a downstream resource can reject a holder that
 * lost the lock (a pause past its lease) but hasn't noticed yet. And the lock recipes
 * default to a 10-second lease, so a pause of a couple of seconds doesn't lose the lock.
 */
class LockFencingTests : StringSpec() {
  private val base = "/locks/${javaClass.simpleName}"

  init {
    "a mutex's fencing token grows past a hold that was lost" {
      connectToEtcd(urls) { client ->
        val path = "$base/mutex"
        client.deleteChildren(path)
        DistributedMutex(client, path).use { first ->
          first.fencingToken shouldBe -1L
          first.lock()
          val stale = first.fencingToken
          (stale > 0L) shouldBe true
          first.lock() // reentrant: the same hold, the same token
          first.fencingToken shouldBe stale

          // The hold is lost (its lease revoked out-of-band), and another client takes the lock
          val lease = client.getResponse("$path/", getOption { isPrefix(true) }).kvs.first().lease
          client.leaseClient.revoke(lease).get()
          DistributedMutex(client, path).use { second ->
            second.tryLock(10.seconds) shouldBe true
            withClue("the successor's token doesn't fence out the stale holder") {
              second.fencingToken shouldBeGreaterThan stale
            }
            second.unlock()
          }
          pollUntil(10.seconds) { !first.isHeldByCurrentThread } shouldBe true
          first.fencingToken shouldBe -1L
        }
        client.deleteChildren(path)
      }
    }

    "read-write lock views hand out fencing tokens in grant order" {
      connectToEtcd(urls) { client ->
        val path = "$base/rw"
        client.deleteChildren(path)
        DistributedReadWriteLock(client, path).use { lock ->
          lock.writeLock.fencingToken shouldBe -1L
          lock.writeLock.lock()
          val written = lock.writeLock.fencingToken
          lock.writeLock.unlock()
          lock.readLock.lock()
          val read = lock.readLock.fencingToken
          lock.readLock.unlock()
          (written > 0L) shouldBe true
          read shouldBeGreaterThan written
          lock.readLock.fencingToken shouldBe -1L
        }
        client.deleteChildren(path)
      }
    }

    "a semaphore permit's fencing token grows with each grant" {
      connectToEtcd(urls) { client ->
        val path = "$base/semaphore"
        client.deleteChildren(path)
        DistributedSemaphore(client, path, 2).use { semaphore ->
          semaphore.fencingToken shouldBe -1L
          semaphore.acquire()
          val first = semaphore.fencingToken
          semaphore.release()
          semaphore.acquire()
          val second = semaphore.fencingToken
          semaphore.release()
          (first > 0L) shouldBe true
          second shouldBeGreaterThan first
          semaphore.fencingToken shouldBe -1L
        }
        client.deleteChildren(path)
      }
    }

    "the lock recipes default to a 10-second lease" {
      connectToEtcd(urls) { client ->
        DistributedMutex(client, "$base/ttl-mutex").use { it.leaseTtlSecs shouldBe 10L }
        DistributedReadWriteLock(client, "$base/ttl-rw").use { it.leaseTtlSecs shouldBe 10L }
        DistributedSemaphore(client, "$base/ttl-semaphore", 1).use { it.leaseTtlSecs shouldBe 10L }
        val recipes = EtcdRecipes(client)
        recipes.mutex("$base/ttl-mutex").use { it.leaseTtlSecs shouldBe 10L }
        recipes.readWriteLock("$base/ttl-rw").use { it.leaseTtlSecs shouldBe 10L }
        recipes.semaphore("$base/ttl-semaphore", 1).use { it.leaseTtlSecs shouldBe 10L }
      }
    }
  }
}
