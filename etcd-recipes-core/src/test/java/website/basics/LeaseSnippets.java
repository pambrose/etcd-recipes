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
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.support.CloseableClient;
import kotlin.Pair;
import kotlin.Unit;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.etcd.recipes.common.BuilderUtils.putOption;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsByteSequence;
import static io.etcd.recipes.common.KVUtils.putValue;
import static io.etcd.recipes.common.KeepAliveUtils.putValueWithKeepAlive;
import static io.etcd.recipes.common.KeepAliveUtils.putValuesWithKeepAlive;
import static io.etcd.recipes.common.LeaseUtils.keepAlive;
import static io.etcd.recipes.common.LeaseUtils.keepAliveWith;
import static io.etcd.recipes.common.LeaseUtils.leaseRevoke;

public class LeaseSnippets {

  public void grant(Client client) throws ExecutionException, InterruptedException {
    // --8<-- [start:grant]
    // A lease is a timer that lives on the etcd server, not in your process. Bind a key to
    // it and etcd deletes that key the instant the lease expires — no client needs to be
    // alive, reachable, or willing for that to happen.
    //
    // LeaseUtils.leaseGrant takes a kotlin.time.Duration, an inline value class, so its
    // JVM name is mangled and Java cannot call it. Grant through jetcd's lease client.
    LeaseGrantResponse lease = client.getLeaseClient().grant(5).get();
    putValue(client, "/services/worker-1", "10.0.0.1:8080",
      putOption(builder -> builder.withLeaseId(lease.getID())));

    // Nothing is renewing it, so the key is gone roughly 5 seconds from now.
    System.out.printf("Lease %d granted with a %ds TTL%n", lease.getID(), lease.getTTL());
    // --8<-- [end:grant]
  }

  public void keepAliveScoped(Client client) throws ExecutionException, InterruptedException {
    // --8<-- [start:keep-alive]
    LeaseGrantResponse lease = client.getLeaseClient().grant(5).get();
    putValue(client, "/services/worker-1", "10.0.0.1:8080",
      putOption(builder -> builder.withLeaseId(lease.getID())));

    // keepAliveWith renews in the background for exactly as long as the block runs, then
    // stops. Java has no default arguments, so the onKeepAliveError callback is not
    // optional here — which is no loss: renewal can otherwise stop silently and the key
    // vanishes while the process still looks healthy.
    keepAliveWith(client, lease,
      error -> {
        System.out.println("Renewal stopped: " + error);
        return Unit.INSTANCE;
      },
      () -> {
        System.out.println("Registered for the duration of this block");
        return Unit.INSTANCE;
      });

    // Best-effort by design: a revoke that fails is logged, not thrown, because the TTL is
    // already the upper bound on how long the lease can outlive you.
    leaseRevoke(client, lease);
    // --8<-- [end:keep-alive]
  }

  public void keepAliveRaw(Client client) throws ExecutionException, InterruptedException {
    // --8<-- [start:keep-alive-raw]
    LeaseGrantResponse lease = client.getLeaseClient().grant(5).get();

    // The unscoped form, when the lease must outlive the current block. You own the close:
    // renewal continues until the returned CloseableClient is closed.
    try (CloseableClient renewal = keepAlive(client, lease)) {
      System.out.println("Renewing lease " + lease.getID());
    }
    // --8<-- [end:keep-alive-raw]
  }

  public void putWithKeepAlive(Client client) {
    // --8<-- [start:put-with-keep-alive]
    // Grant, put, renew, and revoke collapsed into one call: the key lives exactly as long
    // as the block. If a put throws on the way in, the lease is revoked rather than
    // stranded for its TTL. This is the shape every ephemeral recipe is built from.
    putValueWithKeepAlive(client, "/services/worker-1", "10.0.0.1:8080", 5L, () -> {
      System.out.println("Serving; the registration key is renewed underneath us");
      return Unit.INSTANCE;
    });
    // --8<-- [end:put-with-keep-alive]
  }

  public void putSeveralWithKeepAlive(Client client) {
    // --8<-- [start:put-values-with-keep-alive]
    // Several keys on ONE lease, so they appear together and vanish together. A reader can
    // never catch half a registration published and half of it expired.
    List<Pair<String, ByteSequence>> kvs =
      List.of(
        new Pair<>("/services/worker-1/host", getAsByteSequence("10.0.0.1")),
        new Pair<>("/services/worker-1/port", getAsByteSequence(8080)));

    putValuesWithKeepAlive(client, kvs, 5L,
      error -> {
        System.out.println("Renewal stopped: " + error);
        return Unit.INSTANCE;
      },
      () -> {
        System.out.println("Both keys are alive for exactly this block");
        return Unit.INSTANCE;
      });
    // --8<-- [end:put-values-with-keep-alive]
  }
}
