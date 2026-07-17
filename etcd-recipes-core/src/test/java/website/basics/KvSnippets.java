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

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.recipes.common.KeyValueUtils;
import io.etcd.recipes.common.PairUtils;
import io.etcd.recipes.common.RpcResilience;
import io.etcd.recipes.common.StringCodec;
import io.etcd.recipes.common.TypedKVUtils;
import kotlin.Pair;

import java.util.List;

import static io.etcd.recipes.common.BuilderUtils.getOption;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsByteSequence;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsInt;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsLong;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsString;
import static io.etcd.recipes.common.ChildrenUtils.deleteChildren;
import static io.etcd.recipes.common.ChildrenUtils.getChildCount;
import static io.etcd.recipes.common.ChildrenUtils.getChildren;
import static io.etcd.recipes.common.ChildrenUtils.getChildrenKeys;
import static io.etcd.recipes.common.ChildrenUtils.getChildrenValues;
import static io.etcd.recipes.common.ChildrenUtils.getFirstChild;
import static io.etcd.recipes.common.ChildrenUtils.getLastChild;
import static io.etcd.recipes.common.KVUtils.compact;
import static io.etcd.recipes.common.KVUtils.deleteKey;
import static io.etcd.recipes.common.KVUtils.deleteKeys;
import static io.etcd.recipes.common.KVUtils.getKeyValuePairs;
import static io.etcd.recipes.common.KVUtils.getResponse;
import static io.etcd.recipes.common.KVUtils.getValue;
import static io.etcd.recipes.common.KVUtils.isKeyNotPresent;
import static io.etcd.recipes.common.KVUtils.isKeyPresent;
import static io.etcd.recipes.common.KVUtils.putValue;
import static io.etcd.recipes.common.PathUtils.appendToPath;

public class KvSnippets {

  public void putGet(Client client) {
    // --8<-- [start:put-get]
    putValue(client, "/config/name", "orders-service");

    // getValue hands back a null ByteSequence: absent is a real answer, not an error.
    ByteSequence raw = getValue(client, "/config/name");
    System.out.println("Raw value: " + (raw == null ? "null" : getAsString(raw)));

    // The overloads taking a default collapse "absent" into a value you choose.
    String name = getValue(client, "/config/name", "unset");
    System.out.println("Name: " + name);
    // --8<-- [end:put-get]
  }

  public void putTyped(Client client) {
    // --8<-- [start:put-typed]
    // Int and Long are written as fixed-width big-endian bytes, NOT as decimal text,
    // so they round-trip through getAsInt/getAsLong — never through toString/parseInt.
    putValue(client, "/counters/retries", 3);
    putValue(client, "/counters/bytes", 9_000_000_000L);
    putValue(client, "/blobs/payload", getAsByteSequence("raw bytes"));

    int retries = getValue(client, "/counters/retries", 0);
    long bytes = getValue(client, "/counters/bytes", 0L);
    System.out.printf("retries=%d bytes=%d%n", retries, bytes);
    // --8<-- [end:put-typed]
  }

  public void delete(Client client) {
    // --8<-- [start:delete]
    deleteKey(client, "/config/name");

    // deleteKeys is a convenience loop, not an atomic multi-delete: each key is its own
    // RPC, so a failure partway through leaves the earlier deletes applied. Use a
    // transaction when the keys must disappear together.
    deleteKeys(client, "/config/a", "/config/b", "/config/c");
    // --8<-- [end:delete]
  }

  public void keyPresence(Client client) {
    // --8<-- [start:key-presence]
    // Both run a transaction against the key's version rather than fetching its value,
    // so a large value costs nothing to test for.
    if (isKeyPresent(client, "/config/name")) {
      System.out.println("Present");
    }

    if (isKeyNotPresent(client, "/config/missing")) {
      System.out.println("Absent");
    }
    // --8<-- [end:key-presence]
  }

  public void getResponses(Client client) {
    // --8<-- [start:get-response]
    // The full GetResponse, for when you need the revision, the count, or every match.
    GetResponse response = getResponse(client, "/config/name");
    System.out.printf("Revision: %d, count: %d%n",
      response.getHeader().getRevision(), response.getCount());

    // getKeyValuePairs flattens a GetResponse down to (key, value) pairs.
    List<Pair<String, ByteSequence>> pairs =
      getKeyValuePairs(client, "/config/", getOption(builder -> builder.isPrefix(true)));
    System.out.println("Config: " + PairUtils.getAsString(pairs));
    // --8<-- [end:get-response]
  }

  public void children(Client client) {
    // --8<-- [start:children]
    // Every children helper appends a trailing "/" before its prefix GET, so "/services"
    // and "/services/" mean the same thing and "/servicesX" is never swept in by accident.
    List<Pair<String, ByteSequence>> children = getChildren(client, "/services");
    List<String> keys = getChildrenKeys(client, "/services");
    List<ByteSequence> values = getChildrenValues(client, "/services");
    long count = getChildCount(client, "/services");
    System.out.printf("%d children: %s, keys=%s, values=%d%n",
      count, PairUtils.getAsString(children), keys, values.size());

    // Sort by CREATE and first/last become oldest/newest — the server-side ordering that
    // the FIFO recipes (queues, election, barriers) are built on.
    GetResponse oldest = getFirstChild(client, "/services", GetOption.SortTarget.CREATE);
    GetResponse newest = getLastChild(client, "/services", GetOption.SortTarget.CREATE);
    System.out.printf("oldest=%d newest=%d%n", oldest.getCount(), newest.getCount());

    // One ranged delete — atomic, unlike deleteKeys. Returns the keys it removed.
    List<String> deleted = deleteChildren(client, "/services");
    System.out.println("Deleted: " + deleted);
    // --8<-- [end:children]
  }

  public void conversions(Client client) {
    // --8<-- [start:conversions]
    // etcd stores bytes and nothing else; these helpers are the whole marshalling story.
    ByteSequence keyBytes = getAsByteSequence("/config/name");
    ByteSequence countBytes = getAsByteSequence(42);
    ByteSequence stampBytes = getAsByteSequence(1_700_000_000L);
    System.out.printf("%s %d %d%n",
      getAsString(keyBytes), getAsInt(countBytes), getAsLong(stampBytes));

    // Kotlin's extension properties become get-prefixed statics on the facade class:
    // `kv.asPair` in Kotlin is `KeyValueUtils.getAsPair(kv)` here.
    List<KeyValue> kvs =
      getResponse(client, "/config/", getOption(builder -> builder.isPrefix(true))).getKvs();
    if (!kvs.isEmpty()) {
      Pair<String, ByteSequence> first = KeyValueUtils.getAsPair(kvs.get(0));
      System.out.println("First: " + PairUtils.getAsString(first));
    }

    List<Pair<String, ByteSequence>> pairs = getChildren(client, "/services");
    System.out.printf("keys=%s values=%d%n",
      PairUtils.getKeys(pairs), PairUtils.getValues(pairs).size());

    System.out.println(appendToPath("/services", "worker-1"));
    // --8<-- [end:conversions]
  }

  public void compactHistory(Client client) {
    // --8<-- [start:compact]
    long current = getResponse(client, "/config/name").getHeader().getRevision();

    // Discards all history at or below this revision to bound etcd's disk growth. Any
    // watcher still anchored below it dies with a CompactedException — which is exactly
    // what the resilient watcher's resyncWith hook exists to absorb.
    compact(client, current);
    // --8<-- [end:compact]
  }

  public void codec(Client client) {
    // --8<-- [start:codec]
    // TypedKVUtils carries no @JvmOverloads, so Java supplies every argument — there is
    // no shorter form the way Kotlin's default parameters provide one.
    TypedKVUtils.putValue(client, "/config/greeting", "hello",
      StringCodec.INSTANCE, PutOption.DEFAULT, RpcResilience.DEFAULT);

    String greeting =
      TypedKVUtils.getValue(client, "/config/greeting", StringCodec.INSTANCE, RpcResilience.DEFAULT);
    System.out.println("Greeting: " + (greeting == null ? "unset" : greeting));
    // --8<-- [end:codec]
  }
}
