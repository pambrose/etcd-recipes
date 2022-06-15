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

package io.etcd.recipes.barrier

import com.github.pambrose.common.time.timeUnitToDuration
import com.github.pambrose.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.recipes.barrier.DistributedDoubleBarrier.Companion.defaultClientId
import io.etcd.recipes.common.EtcdConnector.Companion.tokenLength
import io.etcd.recipes.common.EtcdRecipeException
import io.etcd.recipes.common.appendToPath
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@JvmOverloads
fun <T> withDistributedDoubleBarrier(
  client: Client,
  barrierPath: String,
  memberCount: Int,
  clientId: String = defaultClientId(),
  receiver: DistributedDoubleBarrier.() -> T
): T =
  DistributedDoubleBarrier(client, barrierPath, memberCount, clientId).use { it.receiver() }

class DistributedDoubleBarrier
@JvmOverloads
constructor(
  client: Client,
  barrierPath: String,
  memberCount: Int,
  val clientId: String = defaultClientId()
) : Closeable {

  private val enterBarrier = DistributedBarrierWithCount(client, barrierPath.appendToPath("enter"), memberCount)
  private val leaveBarrier = DistributedBarrierWithCount(client, barrierPath.appendToPath("leave"), memberCount)

  init {
    require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
  }

  val enterWaiterCount: Long get() = enterBarrier.waiterCount

  val leaveWaiterCount: Long get() = leaveBarrier.waiterCount

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun enter(): Boolean = enter(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun enter(timeout: Long, timeUnit: TimeUnit): Boolean = enter(timeUnitToDuration(timeout, timeUnit))

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun enter(timeout: Duration): Boolean = enterBarrier.waitOnBarrier(timeout)

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun leave(): Boolean = leave(Long.MAX_VALUE.days)

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun leave(timeout: Long, timeUnit: TimeUnit): Boolean = leave(timeUnitToDuration(timeout, timeUnit))

  @Throws(InterruptedException::class, EtcdRecipeException::class)
  fun leave(timeout: Duration): Boolean = leaveBarrier.waitOnBarrier(timeout)

  override fun close() {
    enterBarrier.close()
    leaveBarrier.close()
  }

  companion object {
    internal fun defaultClientId() = "${DistributedDoubleBarrier::class.simpleName}:${randomId(tokenLength)}"
  }
}