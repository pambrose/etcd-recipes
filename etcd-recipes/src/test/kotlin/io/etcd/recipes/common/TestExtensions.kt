/*
 * Copyright © 2026 Paul Ambrose
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

package io.etcd.recipes.common

import com.pambrose.common.concurrent.thread
import com.pambrose.common.util.isNotNull
import org.junit.jupiter.api.Assertions.fail
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

val urls = listOf("http://localhost:2379")

/**
 * Wait up to [timeout] for [predicate] to become true, polling every [poll].
 * Returns true if the predicate succeeded before the deadline, false on timeout.
 * Prefer this over fixed `sleep(...)` "settle" calls in tests — it returns as
 * soon as the condition holds, which usually beats the worst-case settle time.
 */
fun pollUntil(
  timeout: Duration,
  poll: Duration = 50.milliseconds,
  predicate: () -> Boolean,
): Boolean {
  val deadline = System.nanoTime() + timeout.inWholeNanoseconds
  while (true) {
    if (predicate()) return true
    if (System.nanoTime() > deadline) return false
    Thread.sleep(poll.inWholeMilliseconds)
  }
}

fun nonblockingThreads(
  threadCount: Int,
  waitLatch: CountDownLatch? = null,
  block: (index: Int) -> Unit,
): Pair<CountDownLatch, ExceptionHolder> {
  val finishedLatch = CountDownLatch(threadCount)
  val holder = ExceptionHolder()
  repeat(threadCount) {
    thread(finishedLatch) {
      try {
        block(it)
        waitLatch?.await()
      } catch (e: Throwable) {
        holder.exception = e
      }
    }
  }
  return Pair(finishedLatch, holder)
}

fun blockingThreads(
  threadCount: Int,
  block: (index: Int) -> Unit,
) {
  val (finishedLatch, exception) = nonblockingThreads(threadCount, block = block)
  finishedLatch.await()
  exception.checkForException()
}

fun ExceptionHolder.checkForException() {
  if (exception.isNotNull())
    return fail("Exception caught: $exception", exception)
}

fun List<ExceptionHolder>.throwExceptionFromList() {
  val e = firstOrNull { it.exception.isNotNull() }?.exception
  if (e.isNotNull())
    throw e
}

fun List<CountDownLatch>.waitForAll() = forEach { it.await() }

fun threadWithExceptionCheck(block: () -> Unit): Pair<CountDownLatch, ExceptionHolder> {
  val latch = CountDownLatch(1)
  val holder = ExceptionHolder()

  thread {
    try {
      block()
    } catch (e: Throwable) {
      holder.exception = e
    } finally {
      latch.countDown()
    }
  }

  return Pair(latch, holder)
}

fun captureException(
  holder: ExceptionHolder,
  block: () -> Unit,
) {
  try {
    block()
  } catch (e: Throwable) {
    holder.exception = e
  }
}
