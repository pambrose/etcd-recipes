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

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

fun nonblockingThreads(threadCount: Int, block: (index: Int) -> Unit): CountDownLatch {
    val latch = CountDownLatch(threadCount)
    repeat(threadCount) {
        thread {
            try {
                block.invoke(it)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }
    }
    return latch
}

fun blockingThreads(threadCount: Int, block: (index: Int) -> Unit) = nonblockingThreads(threadCount, block).await()