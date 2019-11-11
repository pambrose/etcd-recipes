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

package io.etcd.recipes.queue

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.ensureSuffix
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getFirstChild
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

abstract class AbstractQueue(client: Client,
                             val queuePath: String,
                             private val target: SortTarget) : EtcdConnector(client), Closeable {

    init {
        require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
    }

    fun dequeue(): ByteSequence {
        checkCloseNotCalled()

        /*
        println(client.getFirstChild(queuePath, target = target).kvs
                    .map { p -> p.key.asString to p.value.asString }
                    .joinToString("\n"))
        */

        val childList = client.getFirstChild(queuePath, target).kvs

        if (!childList.isEmpty()) {
            val child = childList.first()
            // If transactional delete fails, then just call self again
            return if (deleteRevKey(child)) child.value else dequeue()
        }

        // No values available, so wait on them
        val watchLatch = CountDownLatch(1)
        val trailingPath = queuePath.ensureSuffix("/").asByteSequence
        val watchOption = watchOption {
            withPrefix(trailingPath)
            withNoDelete(true)
        }
        val keyFound = AtomicReference<KeyValue?>()
        client.watcher(queuePath, watchOption) { watchResponse ->
            watchResponse.events.forEach { watchEvent ->
                if (watchEvent.eventType == WatchEvent.EventType.PUT) {
                    keyFound.compareAndSet(null, watchEvent.keyValue)
                    watchLatch.countDown()
                }
            }

        }.use {
            // Query again in case a value arrived just before watch was created
            val waitingChildList = client.getFirstChild(queuePath, target).kvs
            if (!waitingChildList.isEmpty()) {
                keyFound.compareAndSet(null, waitingChildList.first())
                watchLatch.countDown()
            }
            watchLatch.await()
        }

        val kv: KeyValue = keyFound.get()!!
        // If transactional delete fails, then just call self again
        return if (deleteRevKey(kv)) kv.value else dequeue()
    }

    private fun deleteRevKey(kv: KeyValue): Boolean =
        client.transaction {
            If(equalTo(kv.key, CmpTarget.modRevision(kv.modRevision)))
            Then(deleteOp(kv.key))
        }.isSucceeded
}