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

package io.etcd.recipes.common

import org.slf4j.MDC

/**
 * Runs [body] with [context] as the thread's SLF4J MDC, restoring the previous one after.
 * The watch dispatcher and the lease healer capture their creator's MDC and run each task
 * under it, so their logs (on threads every recipe shares) keep the creator's context,
 * including the recipe identity [EtcdConnector.withRecipeLoggingContext] puts there.
 */
internal inline fun <T> withMdc(
  context: Map<String, String>?,
  body: () -> T,
): T {
  val prior = MDC.getCopyOfContextMap()
  if (context == null) MDC.clear() else MDC.setContextMap(context)
  return try {
    body()
  } finally {
    if (prior == null) MDC.clear() else MDC.setContextMap(prior)
  }
}
