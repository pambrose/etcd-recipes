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

package website.resilience;

import io.etcd.jetcd.Client;
import io.etcd.recipes.cache.PathChildrenCache;
import io.etcd.recipes.common.KVUtils;
import io.etcd.recipes.common.LeaseResilience;
import io.etcd.recipes.common.ResilienceConfig;
import io.etcd.recipes.common.RetryPolicy;
import io.etcd.recipes.common.RpcResilience;
import io.etcd.recipes.common.WatchResilience;

public class ResilienceSnippets {

  public void defaults(Client client) {
    // --8<-- [start:defaults]
    // Java sees the @JvmOverloads-generated constructor without the trailing
    // resilience parameter, so the DEFAULT config applies here too.
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/orders")) {
      cache.start(true);
      System.out.println("Resilient without asking: " + cache.getCurrentDataAsMap().size() + " children");
    }
    // --8<-- [end:defaults]
  }

  public void customConfig(Client client) {
    // --8<-- [start:custom-config]
    ResilienceConfig resilience =
      new ResilienceConfig(
        new WatchResilience(RetryPolicy.exponentialBackoff()),
        new LeaseResilience(RetryPolicy.forever),
        new RpcResilience(RetryPolicy.bounded(8)));

    // Java has no named or default arguments, so every preceding parameter must be
    // supplied positionally: null keeps the cache's own single-threaded executor.
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/orders", null, resilience)) {
      cache.start(true);
    }
    // --8<-- [end:custom-config]
  }

  public void disabled(Client client) {
    // --8<-- [start:disabled]
    // The pre-0.12 behavior, should you want it back: no RPC deadline, no RPC retry,
    // a fatally dead watcher stays dead, an expired lease is gone for good.
    try (PathChildrenCache cache =
           new PathChildrenCache(client, "/cache/orders", null, ResilienceConfig.DISABLED)) {
      cache.start(true);
    }
    // --8<-- [end:disabled]
  }

  public void retryPolicies() {
    // --8<-- [start:retry-policy]
    // Only the no-argument factories are reachable from Java. Every overload that
    // takes a kotlin.time.Duration compiles to a name-mangled JVM signature
    // (bounded-HG0u8IE, exponentialBackoff-LRDsOJo, ...) that Java cannot name.
    RetryPolicy paced = RetryPolicy.exponentialBackoff();  // 250ms -> 15s, x2, +/-25% jitter
    RetryPolicy quick = RetryPolicy.bounded(4);            // 4 attempts, 500ms apart
    System.out.println(paced + " / " + quick + " / " + RetryPolicy.forever + " / " + RetryPolicy.never);
    // --8<-- [end:retry-policy]
  }

  public void perCallResilience(Client client) {
    // --8<-- [start:per-call-rpc]
    // The extension layer takes an RpcResilience per call, so a latency-sensitive
    // read can opt out of retrying without changing the recipe's own config.
    RpcResilience oneShot = new RpcResilience(RetryPolicy.never);
    System.out.println("flag=" + KVUtils.getValue(client, "/config/flag", "off", oneShot));
    // --8<-- [end:per-call-rpc]
  }
}
