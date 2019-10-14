/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.barrier

import com.sudothought.common.time.Conversions.Companion.timeUnitToDuration
import com.sudothought.common.util.randomId
import io.etcd.recipes.common.*
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class DistributedDoubleBarrier(val urls: List<String>,
                               barrierPath: String,
                               memberCount: Int,
                               val clientId: String) : Closeable {

    constructor(urls: List<String>,
                barrierPath: String,
                memberCount: Int) : this(urls, barrierPath, memberCount, "Client:${randomId(7)}")

    private val enterBarrier = DistributedBarrierWithCount(urls, barrierPath.appendToPath("enter"), memberCount)
    private val leaveBarrier = DistributedBarrierWithCount(urls, barrierPath.appendToPath("leave"), memberCount)

    init {
        require(urls.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
    }

    val enterWaiterCount: Long get() = enterBarrier.waiterCount

    val leaveWaiterCount: Long get() = leaveBarrier.waiterCount

    fun enter(): Boolean = enter(Long.MAX_VALUE.days)

    fun enter(timeout: Long, timeUnit: TimeUnit): Boolean = enter(timeUnitToDuration(timeout, timeUnit))

    fun enter(timeout: Duration): Boolean = enterBarrier.waitOnBarrier(timeout)

    fun leave(): Boolean = leave(Long.MAX_VALUE.days)

    fun leave(timeout: Long, timeUnit: TimeUnit): Boolean = leave(timeUnitToDuration(timeout, timeUnit))

    fun leave(timeout: Duration): Boolean = leaveBarrier.waitOnBarrier(timeout)

    override fun close() {
        enterBarrier.close()
        leaveBarrier.close()
    }

    companion object {
        @JvmStatic
        fun delete(urls: List<String>, barrierPath: String) {
            require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
            connectToEtcd(urls) { client ->
                client.withKvClient { kvClient ->
                    // Delete all children
                    kvClient.getKeys(barrierPath).forEach { kvClient.delete(it) }
                }
            }
        }
    }
}