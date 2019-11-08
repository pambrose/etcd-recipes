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
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getOldestChild
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.transaction
import mu.KLogging
import java.io.Closeable

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

        if (childList.isEmpty())
            return dequeue()

        val child = childList.first()
        if (deleteRevKey(child.first))
            return child.second
        else
            return dequeue()
    }

    private fun deleteRevKey(kvKey: String): Boolean {
        val txn =
            kvClient.transaction {
                val kvList: List<KeyValue> = kvClient.getResponse(kvKey).kvs
                val kv = if (kvList.isNotEmpty()) kvList[0] else throw IllegalStateException("Empty KeyValue list")
                If(equalTo(kvKey, CmpTarget.modRevision(kv.modRevision)))
                Then(deleteKey(kvKey))
            }
        return txn.isSucceeded
    }

    companion object : KLogging()
}