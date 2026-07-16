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

package io.etcd.recipes.queue

import io.etcd.jetcd.Client
import io.etcd.recipes.common.EtcdCodec
import io.etcd.recipes.common.ResilienceConfig
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Scopes a [TypedDistributedQueue] to [receiver], closing it on exit. */
@JvmOverloads
fun <T, R> withTypedDistributedQueue(
  client: Client,
  queuePath: String,
  codec: EtcdCodec<T>,
  resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  receiver: TypedDistributedQueue<T>.() -> R,
): R = TypedDistributedQueue(client, queuePath, codec, resilience).use { it.receiver() }

/**
 * A typed FIFO queue: a [DistributedQueue] with values marshalled through [codec], so callers
 * enqueue and dequeue [T] instead of raw `ByteSequence`. The full connector API (`exceptions`,
 * `isHealthy()`, background-exception listeners) stays reachable through [untyped].
 */
class TypedDistributedQueue<T>(
  val untyped: DistributedQueue,
  private val codec: EtcdCodec<T>,
) : Closeable {
  @JvmOverloads
  constructor(
    client: Client,
    queuePath: String,
    codec: EtcdCodec<T>,
    resilience: ResilienceConfig = ResilienceConfig.DEFAULT,
  ) : this(DistributedQueue(client, queuePath, resilience), codec)

  fun enqueue(value: T) = untyped.enqueue(codec.encode(value))

  fun enqueueAll(values: Collection<T>) = untyped.enqueueAll(values.map(codec::encode))

  fun dequeue(): T = codec.decode(untyped.dequeue())

  fun tryDequeue(): T? = untyped.tryDequeue()?.let(codec::decode)

  fun poll(timeout: Duration): T? = untyped.poll(timeout)?.let(codec::decode)

  fun poll(
    timeout: Long,
    timeUnit: TimeUnit,
  ): T? = untyped.poll(timeout, timeUnit)?.let(codec::decode)

  val size: Int get() = untyped.size

  override fun close() = untyped.close()
}
