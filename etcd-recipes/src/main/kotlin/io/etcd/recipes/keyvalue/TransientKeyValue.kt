/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.isNull
import com.github.pambrose.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.recipes.barrier.DistributedDoubleBarrier.Companion.defaultClientId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.putValueWithKeepAlive
import mu.KLogging
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
  leaseTtlSecs: Long = EtcdConnector.defaultTtlSecs,
  autoStart: Boolean = true,
  userExecutor: Executor? = null,
  clientId: String = defaultClientId(),
  receiver: TransientKeyValue.() -> T
): T =
  TransientKeyValue(
    client,
    keyPath,
    keyValue,
    leaseTtlSecs,
    autoStart,
    userExecutor,
    clientId
  ).use { it.receiver() }

class TransientKeyValue
@JvmOverloads
constructor(
  client: Client,
  val keyPath: String,
  val keyValue: String,
  val leaseTtlSecs: Long = defaultTtlSecs,
  autoStart: Boolean = true,
  private val userExecutor: Executor? = null,
  val clientId: String = defaultClientId()
) : EtcdConnector(client) {

  private val executor = userExecutor ?: Executors.newSingleThreadExecutor()
  private val keepAliveWaitLatch = CountDownLatch(1)
  private val keepAliveStartedLatch = CountDownLatch(1)

  init {
    require(keyPath.isNotEmpty()) { "Key path cannot be empty" }

    if (autoStart)
      start()
  }

  @Synchronized
  fun start(): TransientKeyValue {
    if (startCalled)
      throw EtcdRecipeRuntimeException("start() already called")
    checkCloseNotCalled()

    executor.execute {
      try {
        val leaseTtl = leaseTtlSecs.seconds
        logger.debug { "$leaseTtl keep-alive started for $clientId $keyPath" }
        client.putValueWithKeepAlive(keyPath, keyValue, leaseTtl) {
          keepAliveStartedLatch.countDown()
          keepAliveWaitLatch.await()
          logger.debug { "$leaseTtl keep-alive terminated for $clientId $keyPath" }
        }
      } catch (e: Throwable) {
        logger.error(e) { "In start()" }
        exceptionList.value += e
      } finally {
        startThreadComplete.set(true)
      }
    }

    keepAliveStartedLatch.await()
    startCalled = true

    return this
  }

  @Synchronized
  override fun close() {
    if (closeCalled)
      return

    checkStartCalled()

    keepAliveWaitLatch.countDown()
    startThreadComplete.waitUntilTrue()

    if (userExecutor.isNull()) (executor as ExecutorService).shutdown()

    super.close()
  }

  companion object : KLogging() {
    internal fun defaultClientId() = "${TransientKeyValue::class.simpleName}:${randomId(tokenLength)}"
  }
}