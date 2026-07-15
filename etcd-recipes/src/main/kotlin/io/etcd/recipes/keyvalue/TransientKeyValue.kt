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

package io.etcd.recipes.keyvalue

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.LeaseEvent
import io.etcd.recipes.common.LeaseListener
import io.etcd.recipes.common.ResilienceConfig
import io.etcd.recipes.common.SelfHealingKeepAlive
import io.etcd.recipes.common.putOption
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.selfHealingKeepAlive
import io.etcd.recipes.keyvalue.TransientKeyValue.Companion.defaultClientId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@JvmOverloads
fun <T> withTransientKeyValue(
  client: Client,
  keyPath: String,
  keyValue: String,
  leaseTtlSecs: Long = EtcdConnector.DEFAULT_TTL_SECS,
  autoStart: Boolean = true,
  userExecutor: Executor? = null,
  clientId: String = defaultClientId(),
  receiver: TransientKeyValue.() -> T,
): T =
  TransientKeyValue(
    client,
    keyPath,
    keyValue,
    leaseTtlSecs,
    autoStart,
    userExecutor,
    clientId,
  ).use { it.receiver() }

class TransientKeyValue
@JvmOverloads
constructor(
  client: Client,
  val keyPath: String,
  val keyValue: String,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  autoStart: Boolean = true,
  private val userExecutor: Executor? = null,
  val clientId: String = defaultClientId(),
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  private val executor = userExecutor ?: Executors.newSingleThreadExecutor()
  private val keepAliveWaitLatch = CountDownLatch(1)
  private val keepAliveStartedLatch = CountDownLatch(1)
  private val leaseListeners = CopyOnWriteArrayList<LeaseListener>()

  /** Registers a listener for lease lifecycle events (expiry, healing, failure). */
  fun addLeaseListener(listener: LeaseListener) {
    leaseListeners += listener
  }

  fun removeLeaseListener(listener: LeaseListener) {
    leaseListeners -= listener
  }

  init {
    require(keyPath.isNotEmpty()) { "Key path cannot be empty" }

    if (autoStart)
      start()
  }

  override val exceptionContext get() = "TransientKeyValue[$keyPath]"

  @Suppress("TooGenericExceptionCaught")
  @Synchronized
  fun start(): TransientKeyValue {
    if (startCalled.load())
      throw EtcdRecipeRuntimeException("start() already called")
    checkCloseNotCalled()

    executor.execute {
      withRecipeLoggingContext {
        var healer: SelfHealingKeepAlive? = null
        try {
          val leaseTtl = leaseTtlSecs.seconds
          logger.debug { "$leaseTtl keep-alive started for $clientId $keyPath" }
          // Self-healing: if the lease expires (partition longer than the TTL), the
          // healer re-grants it and re-puts the key, instead of the key silently
          // vanishing while this recipe still looks healthy.
          healer = client.selfHealingKeepAlive(
            leaseTtl,
            resilience.lease,
            leaseListener = { event -> onLeaseEvent(event) },
          ) { lease ->
            client.putValue(keyPath, keyValue, putOption { withLeaseId(lease.id) }, resilience.rpc)
            true
          }
          keepAliveStartedLatch.countDown()
          keepAliveWaitLatch.await()
          logger.debug { "$leaseTtl keep-alive terminated for $clientId $keyPath" }
        } catch (e: Throwable) {
          logger.error(e) { "In start()" }
          recordException(e)
        } finally {
          runCatching { healer?.close() }
          // Always release the start() caller, even on failure. The previous
          // version only counted down inside the keepAlive callback, so if the
          // initial put / lease grant threw, start() would block on
          // keepAliveStartedLatch forever.
          keepAliveStartedLatch.countDown()
          startThreadComplete.set(true)
        }
      }
    }

    keepAliveStartedLatch.await()

    // Surface a setup failure to the caller of start() so they don't believe
    // a non-running keepAlive is healthy. Mark startCalled only on success;
    // marking before checking would let close() proceed past checkStartCalled()
    // on an instance that never actually started.
    val startupError = exceptionList.value.firstOrNull()
    if (startupError != null) {
      // Constructor with autoStart=true throws here; the user never gets a
      // reference to call close(), so we must release our owned executor
      // before propagating, otherwise the thread (and JVM exit) leaks.
      if (userExecutor == null) (executor as ExecutorService).shutdown()
      throw EtcdRecipeRuntimeException("start() failed: $startupError")
    }

    startCalled.store(true)
    return this
  }

  override fun doClose() {
    checkStartCalled()

    keepAliveWaitLatch.countDown()
    startThreadComplete.waitUntilTrue()

    if (userExecutor == null) (executor as ExecutorService).shutdown()
  }

  // Record every lease event on the exceptions list the way the old keep-alive
  // error callback did (a caller polling exceptions must still see renewal
  // trouble), drive connection state, and forward to user listeners.
  @Suppress("TooGenericExceptionCaught")
  private fun onLeaseEvent(event: LeaseEvent) {
    withRecipeLoggingContext {
      reportLeaseEvent(event)
      when (event) {
        is LeaseEvent.Suspended -> {
          recordException(event.cause)
        }

        is LeaseEvent.Expired -> {
          event.cause?.let { recordException(it) }
          ?: run { recordException(EtcdRecipeRuntimeException("Lease for $keyPath expired; healing")) }
        }

        is LeaseEvent.Failed -> {
          recordException(
            event.cause
              ?: EtcdRecipeRuntimeException("Lease healing for $keyPath abandoned; key is gone"),
          )
        }

        is LeaseEvent.Restored -> {
          logger.info { "Lease for $keyPath healed: ${event.oldLeaseId} -> ${event.newLeaseId}" }
        }
      }
      leaseListeners.forEach { listener ->
        try {
          listener.onLeaseEvent(event)
        } catch (e: Throwable) {
          logger.error(e) { "Exception in lease listener" }
          recordException(e)
        }
      }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    internal fun defaultClientId() = defaultClientId(TransientKeyValue::class.simpleName!!)
  }
}
