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

package website.basics

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.options.GetOption
import io.etcd.recipes.common.StringCodec
import io.etcd.recipes.common.appendToPath
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asInt
import io.etcd.recipes.common.asLong
import io.etcd.recipes.common.asPair
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.compact
import io.etcd.recipes.common.deleteChildren
import io.etcd.recipes.common.deleteKey
import io.etcd.recipes.common.deleteKeys
import io.etcd.recipes.common.getChildCount
import io.etcd.recipes.common.getChildren
import io.etcd.recipes.common.getChildrenKeys
import io.etcd.recipes.common.getChildrenValues
import io.etcd.recipes.common.getFirstChild
import io.etcd.recipes.common.getKeyValuePairs
import io.etcd.recipes.common.getLastChild
import io.etcd.recipes.common.getOption
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyNotPresent
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.keys
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.values
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun putAndGet(client: Client) {
  // --8<-- [start:put-get]
  client.putValue("/config/name", "orders-service")

  // getValue hands back a nullable ByteSequence: absent is a real answer, not an error.
  val raw: ByteSequence? = client.getValue("/config/name")
  logger.info { "Raw value: ${raw?.asString}" }

  // The overloads taking a default collapse "absent" into a value you choose.
  val name: String = client.getValue("/config/name", "unset")
  logger.info { "Name: $name" }
  // --8<-- [end:put-get]
}

fun putScalars(client: Client) {
  // --8<-- [start:put-typed]
  // Int and Long are written as fixed-width big-endian bytes, NOT as decimal text,
  // so they round-trip through asInt/asLong — never through toString/toInt.
  client.putValue("/counters/retries", 3)
  client.putValue("/counters/bytes", 9_000_000_000L)
  client.putValue("/blobs/payload", "raw bytes".asByteSequence)

  val retries: Int = client.getValue("/counters/retries", 0)
  val bytes: Long = client.getValue("/counters/bytes", 0L)
  logger.info { "retries=$retries bytes=$bytes" }
  // --8<-- [end:put-typed]
}

fun removeKeys(client: Client) {
  // --8<-- [start:delete]
  client.deleteKey("/config/name")

  // deleteKeys is a convenience loop, not an atomic multi-delete: each key is its own
  // RPC, so a failure partway through leaves the earlier deletes applied. Use a
  // transaction when the keys must disappear together.
  client.deleteKeys("/config/a", "/config/b", "/config/c")
  // --8<-- [end:delete]
}

fun checkPresence(client: Client) {
  // --8<-- [start:key-presence]
  // Both run a transaction against the key's version rather than fetching its value,
  // so a large value costs nothing to test for.
  if (client.isKeyPresent("/config/name")) {
    logger.info { "Present" }
  }

  if (client.isKeyNotPresent("/config/missing")) {
    logger.info { "Absent" }
  }
  // --8<-- [end:key-presence]
}

fun readResponses(client: Client) {
  // --8<-- [start:get-response]
  // The full GetResponse, for when you need the revision, the count, or every match.
  val response = client.getResponse("/config/name")
  logger.info { "Revision: ${response.header.revision}, count: ${response.count}" }

  // getKeyValuePairs flattens a GetResponse down to (key, value) pairs.
  val pairs: List<Pair<String, ByteSequence>> =
    client.getKeyValuePairs("/config/", getOption { isPrefix(true) })
  logger.info { "Config: ${pairs.asString}" }
  // --8<-- [end:get-response]
}

fun readChildren(client: Client) {
  // --8<-- [start:children]
  // Every children helper appends a trailing "/" before its prefix GET, so "/services"
  // and "/services/" mean the same thing and "/servicesX" is never swept in by accident.
  val children: List<Pair<String, ByteSequence>> = client.getChildren("/services")
  val keys: List<String> = client.getChildrenKeys("/services")
  val values: List<ByteSequence> = client.getChildrenValues("/services")
  val count: Long = client.getChildCount("/services")
  logger.info { "$count children: ${children.asString}, keys=$keys, values=${values.size}" }

  // Sort by CREATE and first/last become oldest/newest — the server-side ordering that
  // the FIFO recipes (queues, election, barriers) are built on.
  val oldest = client.getFirstChild("/services", GetOption.SortTarget.CREATE)
  val newest = client.getLastChild("/services", GetOption.SortTarget.CREATE)
  logger.info { "oldest=${oldest.count} newest=${newest.count}" }

  // One ranged delete — atomic, unlike deleteKeys. Returns the keys it removed.
  val deleted: List<String> = client.deleteChildren("/services")
  logger.info { "Deleted: $deleted" }
  // --8<-- [end:children]
}

fun conversions(client: Client) {
  // --8<-- [start:conversions]
  // etcd stores bytes and nothing else; these extensions are the whole marshalling story.
  val keyBytes: ByteSequence = "/config/name".asByteSequence
  val countBytes: ByteSequence = 42.asByteSequence
  val stampBytes: ByteSequence = 1_700_000_000L.asByteSequence
  logger.info { "${keyBytes.asString} ${countBytes.asInt} ${stampBytes.asLong}" }

  // KeyValue and Pair get the same treatment, one element or a whole list at a time.
  val kvs = client.getResponse("/config/", getOption { isPrefix(true) }).kvs
  val firstPair: Pair<String, ByteSequence>? = kvs.firstOrNull()?.asPair
  val decoded: List<Pair<String, String>> = kvs.map { it.asPair }.asString
  logger.info { "first=${firstPair?.asString} all=$decoded keys=${decoded.keys} values=${decoded.values}" }

  // appendToPath joins path segments without doubling or dropping the separator.
  logger.info { "/services".appendToPath("worker-1") }
  // --8<-- [end:conversions]
}

fun compactHistory(client: Client) {
  // --8<-- [start:compact]
  val current = client.getResponse("/config/name").header.revision

  // Discards all history at or below this revision to bound etcd's disk growth. Any
  // watcher still anchored below it dies with a CompactedException — which is exactly
  // what the resilient watcher's resyncWith hook exists to absorb.
  client.compact(current)
  // --8<-- [end:compact]
}

fun typedCodec(client: Client) {
  // --8<-- [start:codec]
  // The codec owns encode and decode on both sides, so no hand-marshalling survives
  // at the call site. StringCodec here; jsonCodec<T>() for your own types.
  client.putValue("/config/greeting", "hello", StringCodec)

  val greeting: String? = client.getValue("/config/greeting", StringCodec)
  logger.info { "Greeting: ${greeting ?: "unset"}" }
  // --8<-- [end:codec]
}
