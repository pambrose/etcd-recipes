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

import org.junit.jupiter.api.Assertions.fail
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

fun nonblockingThreads(threadCount: Int,
                       waithLatch: CountDownLatch? = null,
                       block: (index: Int) -> Unit): Pair<CountDownLatch, AtomicReference<Throwable>> {
    val latch = CountDownLatch(threadCount)
    val exception = AtomicReference<Throwable>()
    repeat(threadCount) {
        thread {
            try {
                block(it)
                waithLatch?.await()
            } catch (e: Throwable) {
                exception.set(e)
            } finally {
                latch.countDown()
            }
        }
    }
    return Pair(latch, exception)
}

fun blockingThreads(threadCount: Int, block: (index: Int) -> Unit) {
    val (latch, exception) = nonblockingThreads(threadCount, block = block)
    latch.await()
    exception.checkForException()
}

fun AtomicReference<Throwable>.checkForException() {
    if (get() != null)
        return fail("Exception caught: ${get()}", get())
}

fun List<AtomicReference<Throwable>>.throwExceptionFromList() {
    val e = filter { it.get() != null }.firstOrNull()?.get()
    if (e != null)
        throw e
}

fun List<CountDownLatch>.waitForAll() = forEach { it.await() }

fun threadWithExceptionCheck(block: () -> Unit): Pair<CountDownLatch, AtomicReference<Throwable>> {
    val latch = CountDownLatch(1)
    val exception = AtomicReference<Throwable>()

    thread {
        try {
            block()
        } catch (e: Throwable) {
            exception.set(e)
        } finally {
            latch.countDown()
        }
    }

    return Pair(latch, exception)
}

fun captureException(exceptionRef: AtomicReference<Throwable>, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        exceptionRef.set(e)
    }
}