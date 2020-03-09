/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.queue

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getLastChild
import io.etcd.recipes.common.lessThan
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction

fun <T> withDistributedPriorityQueue(client: Client,
                                     queuePath: String,
                                     receiver: DistributedPriorityQueue.() -> T): T =
    DistributedPriorityQueue(client, queuePath).use { it.receiver() }

class DistributedPriorityQueue(client: Client, queuePath: String) : AbstractQueue(client, queuePath, SortTarget.KEY) {

    fun enqueue(value: String, priority: Int) = enqueue(value.asByteSequence, priority.toUShort())
    fun enqueue(value: Int, priority: Int) = enqueue(value.asByteSequence, priority.toUShort())
    fun enqueue(value: Long, priority: Int) = enqueue(value.asByteSequence, priority.toUShort())
    fun enqueue(value: ByteSequence, priority: Int) = enqueue(value, priority.toUShort())

    fun enqueue(value: String, priority: UShort) = enqueue(value.asByteSequence, priority)
    fun enqueue(value: Int, priority: UShort) = enqueue(value.asByteSequence, priority)
    fun enqueue(value: Long, priority: UShort) = enqueue(value.asByteSequence, priority)

    fun enqueue(value: ByteSequence, priority: UShort) {
        checkCloseNotCalled()
        val prefix = "%s/%05d".format(queuePath, priority.toInt())
        newSequentialKV(prefix, value)
    }

    private fun newSequentialKV(prefix: String, value: ByteSequence) {
        val resp = client.getLastChild(prefix, SortTarget.KEY)
        val kvs = resp.kvs

        var newSeqNum = 0
        if (kvs.isNotEmpty()) {
            val fields = kvs.first().key.asString.split("/")
            newSeqNum = fields[(fields.size) - 1].toInt() + 1
        }

        val txn =
            client.transaction {
                val newKey = "%s/%016d".format(prefix, newSeqNum)
                val baseKey = "__$prefix"
                If(lessThan(baseKey, CmpTarget.modRevision(resp.header.revision + 1)))
                Then(baseKey setTo "", newKey setTo value)
            }

        if (!txn.isSucceeded)
            newSequentialKV(prefix, value)
    }
}