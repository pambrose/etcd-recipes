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

package io.etcd.recipes.keyvalue

import com.sudothought.common.util.randomId
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
import kotlin.time.seconds

@JvmOverloads
fun <T> withTransientNode(client: Client,
                          nodePath: String,
                          nodeValue: String,
                          leaseTtlSecs: Long = EtcdConnector.defaultTtlSecs,
                          autoStart: Boolean = true,
                          userExecutor: Executor? = null,
                          clientId: String = defaultClientId(),
                          receiver: TransientNode.() -> T): T =
    TransientNode(client,
                  nodePath,
                  nodeValue,
                  leaseTtlSecs,
                  autoStart,
                  userExecutor,
                  clientId).use { it.receiver() }

class TransientNode
@JvmOverloads
constructor(client: Client,
            val nodePath: String,
            val nodeValue: String,
            val leaseTtlSecs: Long = defaultTtlSecs,
            autoStart: Boolean = true,
            private val userExecutor: Executor? = null,
            val clientId: String = defaultClientId()) : EtcdConnector(client) {

    private val executor = userExecutor ?: Executors.newSingleThreadExecutor()
    private val keepAliveWaitLatch = CountDownLatch(1)
    private val keepAliveStartedLatch = CountDownLatch(1)

    init {
        require(nodePath.isNotEmpty()) { "Node path cannot be empty" }

        if (autoStart)
            start()
    }

    @Synchronized
    fun start(): TransientNode {
        if (startCalled)
            throw EtcdRecipeRuntimeException("start() already called")
        checkCloseNotCalled()

        executor.execute {
            try {
                val leaseTtl = leaseTtlSecs.seconds
                logger.info { "$leaseTtl keep-alive started for $clientId $nodePath" }
                client.putValueWithKeepAlive(nodePath, nodeValue, leaseTtl) {
                    keepAliveStartedLatch.countDown()
                    keepAliveWaitLatch.await()
                    logger.info { "$leaseTtl keep-alive terminated for $clientId $nodePath" }
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

        if (userExecutor == null) (executor as ExecutorService).shutdown()

        super.close()
    }

    companion object : KLogging() {
        internal fun defaultClientId() = "${TransientNode::class.simpleName}:${randomId(tokenLength)}"
    }
}