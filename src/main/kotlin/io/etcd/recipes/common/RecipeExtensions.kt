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

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue

fun withDistributedQueue(client: Client,
                         queuePath: String,
                         receiver: DistributedQueue.() -> Unit) {
    DistributedQueue(client, queuePath).use { queue -> queue.receiver() }
}

fun withDistributedPriorityQueue(client: Client,
                                 queuePath: String,
                                 receiver: DistributedPriorityQueue.() -> Unit) {
    DistributedPriorityQueue(client, queuePath).use { queue -> queue.receiver() }
}

@JvmOverloads
fun withDistributedAtomicLong(client: Client,
                              counterPath: String,
                              default: Long = 0L,
                              receiver: DistributedAtomicLong.() -> Unit) {
    DistributedAtomicLong(client, counterPath, default).use { counter -> counter.receiver() }
}