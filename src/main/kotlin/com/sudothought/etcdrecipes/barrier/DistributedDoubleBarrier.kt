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

package com.sudothought.etcdrecipes.barrier

import com.sudothought.common.time.Conversions.Static.timeUnitToDuration
import com.sudothought.common.util.randomId
import com.sudothought.etcdrecipes.jetcd.appendToPath
import com.sudothought.etcdrecipes.jetcd.delete
import com.sudothought.etcdrecipes.jetcd.getChildrenKeys
import com.sudothought.etcdrecipes.jetcd.withKvClient
import io.etcd.jetcd.Client
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class DistributedDoubleBarrier(val url: String,
                               barrierPath: String,
                               memberCount: Int,
                               val clientId: String) : Closeable {

    constructor(url: String,
                barrierPath: String,
                memberCount: Int) : this(url, barrierPath, memberCount, "Client:${randomId(9)}")

    private val enterBarrier = DistributedBarrierWithCount(url, barrierPath.appendToPath("enter"), memberCount)
    private val leaveBarrier = DistributedBarrierWithCount(url, barrierPath.appendToPath("leave"), memberCount)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
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

    companion object Static {
        fun reset(url: String, barrierPath: String) {
            require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        // Delete all children
                        kvClient.getChildrenKeys(barrierPath).forEach { kvClient.delete(it) }
                    }
                }
        }
    }
}