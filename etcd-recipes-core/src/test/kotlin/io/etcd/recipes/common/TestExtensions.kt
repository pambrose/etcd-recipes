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
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val LOCAL_ETCD_HOST = "localhost"
private const val LOCAL_ETCD_PORT = 2379

val urls: List<String> by lazy {
  if (System.getProperty("etcd.recipes.testcontainers") == "true") {
    [EtcdTestContainer.endpoint()]
  } else {
    assertLocalEtcdReachable(LOCAL_ETCD_HOST, LOCAL_ETCD_PORT)
    ["http://$LOCAL_ETCD_HOST:$LOCAL_ETCD_PORT"]
  }
}

/**
 * Fail fast if nothing is accepting TCP connections at [host]:[port]. Runs only on the
 * local-etcd path; the Testcontainers path waits for readiness before handing back an
 * endpoint. Without this, jetcd blocks indefinitely on the initial connection when the
 * local etcd was never started, so the whole suite hangs instead of failing with a
 * clear message.
 */
private fun assertLocalEtcdReachable(
  host: String,
  port: Int,
  timeout: Duration = 2.seconds,
) {
  try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeout.inWholeMilliseconds.toInt()) }
  } catch (e: IOException) {
    error(
      """
      No etcd reachable at $host:$port (${e.message}).
      Start a local etcd with ./etcd.sh, or run the suite under Testcontainers:
        make tests-tc      (./gradlew check -PuseTestcontainers)
      """.trimIndent(),
    )
  }
}

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

/**
 * How long a test waits on its own threads or latches before failing. Kotest's timeout is
 * coroutine-based and cannot interrupt a thread blocked in `CountDownLatch.await()`, so an
 * unbounded wait turns a deadlock regression into a hang instead of a failure.
 */
val TEST_WAIT_LIMIT = 2.minutes

fun blockingThreads(
  threadCount: Int,
  timeout: Duration = TEST_WAIT_LIMIT,
  block: (index: Int) -> Unit,
) {
  val (finishedLatch, exception) = nonblockingThreads(threadCount, block = block)
  if (!finishedLatch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    throw AssertionError("${finishedLatch.count} of $threadCount threads still running after $timeout")
  exception.checkForException()
}

/** A bounded [CountDownLatch.await]: fails, naming [what], if the latch is still closed after [timeout]. */
fun CountDownLatch.awaitOrFail(
  timeout: Duration = TEST_WAIT_LIMIT,
  what: String = "latch",
) {
  if (!await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
    throw AssertionError("Timed out after $timeout waiting for $what (count still $count)")
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

fun List<CountDownLatch>.waitForAll(timeout: Duration = TEST_WAIT_LIMIT) =
  forEachIndexed { index, latch -> latch.awaitOrFail(timeout, "latch $index of $size") }

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

/**
 * Atomically raise this counter to [candidate] when it is larger (a running max). The Kotlin
 * atomics API has no `accumulateAndGet`, so tests tracking a high-water mark use this CAS loop.
 */
fun AtomicInt.accumulateMax(candidate: Int) {
  while (true) {
    val current = load()
    if (candidate <= current || compareAndSet(current, candidate)) break
  }
}
