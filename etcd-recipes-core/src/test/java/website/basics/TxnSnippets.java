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

package website.basics;

import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.CmpTarget;

import java.util.List;

import static io.etcd.recipes.common.ByteSequenceUtils.getAsByteSequence;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsLong;
import static io.etcd.recipes.common.KVUtils.getResponse;
import static io.etcd.recipes.common.TxnUtils.deleteOp;
import static io.etcd.recipes.common.TxnUtils.equalTo;
import static io.etcd.recipes.common.TxnUtils.getDoesExist;
import static io.etcd.recipes.common.TxnUtils.getDoesNotExist;
import static io.etcd.recipes.common.TxnUtils.greaterThan;
import static io.etcd.recipes.common.TxnUtils.lessThan;
import static io.etcd.recipes.common.TxnUtils.setTo;
import static io.etcd.recipes.common.TxnUtils.transaction;

public class TxnSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    // An etcd transaction is one atomic server-side if/then/else: evaluate the compares,
    // then run either the Then ops or the Else ops. One round trip, no lock required.
    TxnResponse response = transaction(client, txn -> {
      txn.If(getDoesExist("/config/name"));
      txn.Then(setTo("/audit/last", "found"));
      txn.Else(setTo("/audit/last", "missing"));
      return txn;
    });

    // isSucceeded reports which branch ran — whether the compares held, not whether the
    // RPC worked. A failed RPC throws.
    System.out.println("Compares held: " + response.isSucceeded());
    // --8<-- [end:basic]
  }

  public void compare(Client client) {
    // --8<-- [start:compare]
    // The first argument is the KEY being compared, not a value. CmpTarget chooses which
    // of that key's fields the comparison reads: version, createRevision, modRevision,
    // or value. Several compares in one If() are ANDed together.
    transaction(client, txn -> {
      txn.If(
        equalTo("/config/name", CmpTarget.value(getAsByteSequence("orders-service"))),
        greaterThan("/config/name", CmpTarget.version(0)),
        lessThan("/config/name", CmpTarget.modRevision(500L)));
      txn.Then(setTo("/audit/last", "all three held"));
      return txn;
    });
    // --8<-- [end:compare]
  }

  public void exists(Client client) {
    // --8<-- [start:exists]
    // Kotlin's `"/locks/leader".doesNotExist` extension property becomes a get-prefixed
    // static here. Both read the key's version: a key that was never created — or has
    // since been deleted — has version 0.
    boolean claimed = transaction(client, txn -> {
      txn.If(getDoesNotExist("/locks/leader"));
      txn.Then(setTo("/locks/leader", "node-1"));
      return txn;
    }).isSucceeded();

    // This is create-if-absent as ONE atomic step. A get-then-put would race: two clients
    // could both read "absent" and both write.
    System.out.println("Claimed leadership: " + claimed);
    // --8<-- [end:exists]
  }

  public void deleteOperation(Client client) {
    // --8<-- [start:delete-op]
    // Then and Else take Ops, so a transaction can delete as well as put, and every op in
    // the branch lands atomically with the others.
    transaction(client, txn -> {
      txn.If(getDoesExist("/queue/head"));
      txn.Then(deleteOp("/queue/head"), setTo("/queue/consumed", 1));
      txn.Else(setTo("/audit/last", "queue was empty"));
      return txn;
    });
    // --8<-- [end:delete-op]
  }

  public void cas(Client client) {
    // --8<-- [start:cas]
    // The CAS loop under DistributedAtomicLong: read the value along with its modRevision,
    // then commit only if nobody has touched the key since that read.
    boolean committed = false;
    while (!committed) {
      List<KeyValue> kvs = getResponse(client, "/counters/hits").getKvs();
      KeyValue kv = kvs.get(0);
      long next = getAsLong(kv.getValue()) + 1;

      TxnResponse response = transaction(client, txn -> {
        txn.If(equalTo("/counters/hits", CmpTarget.modRevision(kv.getModRevision())));
        txn.Then(setTo("/counters/hits", next));
        return txn;
      });

      // A false isSucceeded means a concurrent writer won the race, so the read is stale
      // and the loop retries. Transactions are deliberately never retried for you: a
      // failed commit is ambiguous (it may have applied), so the decision stays yours.
      committed = response.isSucceeded();
    }
    // --8<-- [end:cas]
  }
}
