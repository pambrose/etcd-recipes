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
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.op.CmpTarget
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.getLastChildByKey
import io.etcd.recipes.common.greaterThan
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import mu.KLogging
import java.io.Closeable

class DistributedPriorityQueue(val urls: List<String>, val queuePath: String) : EtcdConnector(urls), Closeable {

    init {
        require(queuePath.isNotEmpty()) { "Queue path cannot be empty" }
    }

    fun enqueue(value: String, priority: Int) = enqueue(value.asByteSequence, priority)
    fun enqueue(value: Int, priority: Int) = enqueue(value.asByteSequence, priority)
    fun enqueue(value: Long, priority: Int) = enqueue(value.asByteSequence, priority)

    fun enqueue(value: ByteSequence, priority: Int): PutResponse {
        checkCloseNotCalled()
        val prefix = "%s/%05d".format(queuePath, priority)

        val resp = kvClient.getLastChildByKey(prefix)
        val kvs = resp.kvs

        var newSeqNum = 0
        if (kvs.isNotEmpty()) {
            val fields = kvs.first().key.asString.split("/")
            newSeqNum = fields[(fields.size) - 1].toInt() + 1
        }

        val newKey = "%s/%016d".format(prefix, newSeqNum)
        println("Newkey = $newKey")

        val baseKey = "__$prefix"

        val txn =
            kvClient.transaction {
                If(greaterThan(resp.header.revision.asByteSequence,
                               CmpTarget.ModRevisionCmpTarget.value(baseKey.asByteSequence)))
                Then(baseKey setTo "", newKey setTo value)
            }

        return kvClient.putValue("$queuePath/${System.currentTimeMillis()}-${randomId(3)}", value)
    }

    companion object : KLogging()

}