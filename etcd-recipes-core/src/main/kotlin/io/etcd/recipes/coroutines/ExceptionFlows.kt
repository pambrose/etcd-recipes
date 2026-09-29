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

package io.etcd.recipes.coroutines

import io.etcd.recipes.common.BackgroundException
import io.etcd.recipes.common.BackgroundExceptionListener
import io.etcd.recipes.common.EtcdConnector
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * This recipe's background failures as a [Flow] of [BackgroundException] — the push
 * counterpart to the pull-only [EtcdConnector.exceptions] list. Collection registers a
 * [BackgroundExceptionListener] and cancellation removes it; it never starts or closes the
 * recipe.
 *
 * The listener runs on the recipe's notifier thread, which must never block, so the
 * channel is buffered and sends never wait: unlimited by default (a failure is never
 * dropped), or, with a bounded [capacity], a slow collector loses the oldest failures.
 */
fun EtcdConnector.backgroundExceptionsAsFlow(capacity: Int = Channel.UNLIMITED): Flow<BackgroundException> =
  callbackFlow {
    val listener =
      BackgroundExceptionListener { context, throwable ->
        trySend(BackgroundException(context, throwable))
      }
    addBackgroundExceptionListener(listener)
    awaitClose { removeBackgroundExceptionListener(listener) }
  }.buffer(capacity, overflowFor(capacity))

// A bounded buffer drops its oldest entry rather than suspend the sender. Unlimited and
// conflated buffers never suspend a sender anyway (conflated also rejects any other policy).
private fun overflowFor(capacity: Int): BufferOverflow =
  if (capacity == Channel.UNLIMITED ||
    capacity == Channel.CONFLATED
  )
    BufferOverflow.SUSPEND
    else
    BufferOverflow.DROP_OLDEST
