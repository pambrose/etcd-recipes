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

import com.sudothought.common.util.length
import com.sudothought.common.util.randomId
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.putValue

fun <T> withDistributedQueue(client: Client,
                             queuePath: String,
                             receiver: DistributedQueue.() -> T): T =
    DistributedQueue(client, queuePath).use { it.receiver() }

class DistributedQueue(client: Client, queuePath: String) : AbstractQueue(client, queuePath, SortTarget.MOD) {

    fun enqueue(value: String) = enqueue(value.asByteSequence)
    fun enqueue(value: Int) = enqueue(value.asByteSequence)
    fun enqueue(value: Long) = enqueue(value.asByteSequence)

    fun enqueue(value: ByteSequence) {
        checkCloseNotCalled()
        val key = keyFormat.format(queuePath, System.currentTimeMillis(), randomId(3))
        client.putValue(key, value)
    }

    companion object {
        private val keyFormat = "%s/%0${Long.MAX_VALUE.length()}d-%s"
    }
}