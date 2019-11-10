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

package io.etcd.recipes.node

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.delegate.AtomicDelegates
import com.sudothought.common.util.randomId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.EtcdRecipeRuntimeException
import io.etcd.recipes.common.putValueWithKeepAlive
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.seconds

class TtlNode
@JvmOverloads
constructor(urls: List<String>,
            val nodePath: String,
            val nodeValue: String,
            val leaseTtlSecs: Long = defaultTtlSecs,
            autoStart: Boolean = true,
            private val userExecutor: Executor? = null,
            val clientId: String = defaultClientId()) : EtcdConnector(urls), Closeable {

    private val executor = userExecutor ?: Executors.newSingleThreadExecutor()
    private val startThreadComplete = BooleanMonitor(false)
    private var startCalled by AtomicDelegates.atomicBoolean(false)
    private val keepAliveWaitLatch = CountDownLatch(1)
    private val keepAliveStartedLatch = CountDownLatch(1)

    init {
        require(nodePath.isNotEmpty()) { "Node path cannot be empty" }

        if (autoStart)
            start()
    }

    @Synchronized
    fun start(): TtlNode {
        if (startCalled)
            throw EtcdRecipeRuntimeException("start() already called")
        checkCloseNotCalled()

        executor.execute {
            try {
                val leaseTtl = leaseTtlSecs.seconds
                logger.info { "$leaseTtl keep-alive started for $clientId $nodePath" }
                kvClient.value.putValueWithKeepAlive(client.value, nodePath, nodeValue, leaseTtl) {
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

    private fun checkStartCalled() {
        if (!startCalled) throw EtcdRecipeRuntimeException("start() not called")
    }

    @Synchronized
    override fun close() {
        if (closeCalled)
            return

        checkStartCalled()

        keepAliveWaitLatch.countDown()
        startThreadComplete.waitUntilTrue()

        // Close client and kvClient before shutting down executor
        super.close()

        if (userExecutor == null) (executor as ExecutorService).shutdown()
    }

    companion object : KLogging() {
        private fun defaultClientId() = "${TtlNode::class.simpleName}:${randomId(tokenLength)}"
    }
}