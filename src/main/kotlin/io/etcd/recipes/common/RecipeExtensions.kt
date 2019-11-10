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

package io.etcd.recipes.common

import io.etcd.recipes.counter.DistributedAtomicLong
import io.etcd.recipes.queue.DistributedPriorityQueue
import io.etcd.recipes.queue.DistributedQueue

fun withDistributedQueue(urls: List<String>,
                         queuePath: String,
                         receiver: DistributedQueue.() -> Unit) {
    DistributedQueue(urls, queuePath).use { queue ->
        queue.receiver()
    }
}

fun withDistributedPriorityQueue(urls: List<String>,
                                 queuePath: String,
                                 receiver: DistributedPriorityQueue.() -> Unit) {
    DistributedPriorityQueue(urls, queuePath).use { queue ->
        queue.receiver()
    }
}

@JvmOverloads
fun withDistributedAtomicLong(urls: List<String>,
                              counterPath: String,
                              default: Long = 0L,
                              receiver: DistributedAtomicLong.() -> Unit) {
    DistributedAtomicLong(urls, counterPath, default).use { counter ->
        counter.receiver()
    }
}