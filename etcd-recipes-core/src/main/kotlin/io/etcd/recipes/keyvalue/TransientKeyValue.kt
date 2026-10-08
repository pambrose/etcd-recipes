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
import java.util.concurrent.Executor
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

/**
 * Publishes [keyValue] at [keyPath] for as long as this instance is open, under a self-healing
 * lease: if the lease expires (a partition longer than [leaseTtlSecs]), it is re-granted and
 * the key re-published. [close] removes the key promptly.
 *
 * `userExecutor` is no longer used: publishing needs no thread of its own (the lease's
 * renewal and healing run on internal threads). It remains for source and binary compatibility.
 */
class TransientKeyValue
@JvmOverloads
constructor(
  client: Client,
  val keyPath: String,
  val keyValue: String,
  val leaseTtlSecs: Long = DEFAULT_TTL_SECS,
  autoStart: Boolean = true,
  @Suppress("UNUSED_PARAMETER", "unused") userExecutor: Executor? = null,
  val clientId: String = defaultClientId(),
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
) : EtcdConnector(client, resilience) {
  // The published key's lease, renewed (and healed) while this instance is open
  @Volatile
  private var healer: SelfHealingKeepAlive? = null
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

  /**
   * Publishes the key and returns once it is published. A failure throws
   * [EtcdRecipeRuntimeException] and leaves the instance unstarted, so `start()` can be retried.
   */
  @Suppress("TooGenericExceptionCaught")
  @Synchronized
  fun start(): TransientKeyValue {
    if (startCalled.load())
      throw EtcdRecipeRuntimeException("start() already called")
    checkCloseNotCalled()

    healer =
      try {
        // Self-healing: if the lease expires (partition longer than the TTL), the
        // healer re-grants it and re-puts the key, instead of the key silently
        // vanishing while this recipe still looks healthy.
        withRecipeLoggingContext {
          client.selfHealingKeepAlive(
            leaseTtlSecs.seconds,
            resilience.lease,
            leaseListener = { event -> onLeaseEvent(event) },
            rpc = resilience.rpc,
          ) { lease ->
            client.putValue(keyPath, keyValue, putOption { withLeaseId(lease.id) }, resilience.rpc)
            true
          }
        }
      } catch (e: Exception) {
        // Nothing was left behind (a failed establish revokes its lease), so a retry starts clean
        recordException(e)
        throw EtcdRecipeRuntimeException("start() failed for $keyPath", e)
      }

    startCalled.store(true)
    startThreadComplete.set(true)
    return this
  }

  // Revokes the lease, which removes the key. A no-op on an instance that never started.
  @Synchronized
  override fun doClose() {
    healer?.close()
    healer = null
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
