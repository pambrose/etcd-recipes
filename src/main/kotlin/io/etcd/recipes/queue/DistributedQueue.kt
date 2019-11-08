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

import com.sudothought.common.util.randomId
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KeyValue
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.ensureSuffix
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getOldestChild
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.transaction
import io.etcd.recipes.common.watchOption
import io.etcd.recipes.common.watcher
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class DistributedQueue(val urls: List<String>,
                       val queuePath: String) : EtcdConnector(urls), Closeable {


    init {
        require(urls.isNotEmpty()) { "URLs cannot be empty" }
        require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
    }

    fun enqueue(value: String) = enqueue(value.asByteSequence)
    fun enqueue(value: Int) = enqueue(value.asByteSequence)
    fun enqueue(value: Long) = enqueue(value.asByteSequence)

    fun enqueue(value: ByteSequence): PutResponse {
        checkCloseNotCalled()
        return kvClient.putValue("$queuePath/${System.currentTimeMillis()}-${randomId(3)}", value)
    }

    fun dequeue(): ByteSequence {
        checkCloseNotCalled()
        val childList = kvClient.getOldestChild(queuePath)

        if (!childList.isEmpty()) {
            val child = childList.first()
            // If transactional delete fails, then just call self again
            return if (deleteRevKey(child)) child.value else dequeue()
        }

        // No values available, so wait on them
        val watchLatch = CountDownLatch(1)
        val trailingPath = queuePath.ensureSuffix("/").asByteSequence
        val watchOption = watchOption { withPrefix(trailingPath) }
        val keyFound = AtomicReference<KeyValue>()
        watchClient.watcher(queuePath, watchOption) { watchResponse ->
            watchResponse.events.forEach { watchEvent ->
                if (watchEvent.eventType == PUT)
                    keyFound.set((watchEvent.keyValue))
                watchLatch.countDown()
            }

        }.use {
            // Query again in case a value arrived just before watch was created
            val waitingChildList = kvClient.getOldestChild(queuePath)
            if (!waitingChildList.isEmpty()) {
                keyFound.set(waitingChildList.first())
                watchLatch.countDown()
            }
            watchLatch.await()
        }

        val kv: KeyValue = keyFound.get()
        // If transactional delete fails, then just call self again
        return if (deleteRevKey(kv)) kv.value else dequeue()
    }

    private fun deleteRevKey(kv: KeyValue): Boolean {
        val txn =
            kvClient.transaction {
                If(equalTo(kv.key, CmpTarget.modRevision(kv.modRevision)))
                Then(deleteKey(kv.key))
            }
        return txn.isSucceeded
    }

    companion object : KLogging()
}