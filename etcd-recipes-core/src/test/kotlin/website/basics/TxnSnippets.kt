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

import io.etcd.jetcd.Client
import io.etcd.jetcd.op.CmpTarget
import io.etcd.recipes.common.asByteSequence
import io.etcd.recipes.common.asLong
import io.etcd.recipes.common.deleteOp
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.doesNotExist
import io.etcd.recipes.common.equalTo
import io.etcd.recipes.common.getResponse
import io.etcd.recipes.common.greaterThan
import io.etcd.recipes.common.lessThan
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

fun basicTransaction(client: Client) {
  // --8<-- [start:basic]
  // An etcd transaction is one atomic server-side if/then/else: evaluate the compares,
  // then run either the Then ops or the Else ops. One round trip, no lock required.
  val response =
    client.transaction {
      If("/config/name".doesExist)
      Then("/audit/last" setTo "found")
      Else("/audit/last" setTo "missing")
    }

  // isSucceeded reports which branch ran — whether the compares held, not whether the
  // RPC worked. A failed RPC throws.
  logger.info { "Compares held: ${response.isSucceeded}" }
  // --8<-- [end:basic]
}

fun comparisons(client: Client) {
  // --8<-- [start:compare]
  // The first argument is the KEY being compared, not a value. CmpTarget chooses which
  // of that key's fields the comparison reads: version, createRevision, modRevision,
  // or value. Several compares in one If() are ANDed together.
  client.transaction {
    If(
      equalTo("/config/name", CmpTarget.value("orders-service".asByteSequence)),
      greaterThan("/config/name", CmpTarget.version(0)),
      lessThan("/config/name", CmpTarget.modRevision(500L)),
    )
    Then("/audit/last" setTo "all three held")
  }
  // --8<-- [end:compare]
}

fun existenceChecks(client: Client) {
  // --8<-- [start:exists]
  // doesExist / doesNotExist are sugar over the key's version: a key that was never
  // created — or has since been deleted — has version 0.
  val claimed =
    client
      .transaction {
        If("/locks/leader".doesNotExist)
        Then("/locks/leader" setTo "node-1")
      }.isSucceeded

  // This is create-if-absent as ONE atomic step. A get-then-put would race: two clients
  // could both read "absent" and both write.
  logger.info { "Claimed leadership: $claimed" }
  // --8<-- [end:exists]
}

fun deleteWithinTransaction(client: Client) {
  // --8<-- [start:delete-op]
  // Then and Else take Ops, so a transaction can delete as well as put, and every op in
  // the branch lands atomically with the others.
  client.transaction {
    If("/queue/head".doesExist)
    Then(deleteOp("/queue/head"), "/queue/consumed" setTo 1)
    Else("/audit/last" setTo "queue was empty")
  }
  // --8<-- [end:delete-op]
}

fun compareAndSwap(client: Client) {
  // --8<-- [start:cas]
  // The CAS loop under DistributedAtomicLong: read the value along with its modRevision,
  // then commit only if nobody has touched the key since that read.
  var committed = false
  while (!committed) {
    val kv = client.getResponse("/counters/hits").kvs.first()
    val next = kv.value.asLong + 1

    val response =
      client.transaction {
        If(equalTo("/counters/hits", CmpTarget.modRevision(kv.modRevision)))
        Then("/counters/hits" setTo next)
      }

    // A false isSucceeded means a concurrent writer won the race, so the read is stale
    // and the loop retries. Transactions are deliberately never retried for you: a
    // failed commit is ambiguous (it may have applied), so the decision stays yours.
    committed = response.isSucceeded
  }
  // --8<-- [end:cas]
}
