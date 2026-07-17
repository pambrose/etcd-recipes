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

package website.keyvalue;

import io.etcd.jetcd.Client;
import io.etcd.recipes.common.LeaseEvent;
import io.etcd.recipes.keyvalue.TransientKeyValue;

public class TransientKeyValueSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    // autoStart defaults to true: by the time the constructor returns, the lease has
    // been granted, the key put, and the keep-alive started.
    try (TransientKeyValue kv =
           new TransientKeyValue(client, "/nodes/node-1", "10.0.0.7:8080", 5L)) {
      // The key exists for exactly as long as this recipe is open and renewing.
      System.out.println("Published " + kv.getKeyPath() + " = " + kv.getKeyValue());
    }
    // close() stops the keep-alive; etcd drops the key when the lease runs out.
    // --8<-- [end:basic]
  }

  public void deferredStart(Client client) {
    // --8<-- [start:deferred-start]
    // autoStart = false defers every RPC to start(). Java has no named arguments, so
    // leaseTtlSecs must be supplied positionally to reach the autoStart parameter.
    try (TransientKeyValue kv =
           new TransientKeyValue(client, "/nodes/node-1", "10.0.0.7:8080", 5L, false)) {
      // One-shot: a second start() throws EtcdRecipeRuntimeException.
      kv.start();
      System.out.println("Publishing " + kv.getKeyPath());
    }
    // --8<-- [end:deferred-start]
  }

  public void leaseListener(Client client) {
    // --8<-- [start:lease-listener]
    try (TransientKeyValue kv =
           new TransientKeyValue(client, "/nodes/node-1", "10.0.0.7:8080", 5L, false)) {
      // Unlike a lock or election lease, this one IS healed: on expiry the recipe
      // re-grants a lease and re-puts the key. Registering the listener before
      // start() is only possible with autoStart = false.
      kv.addLeaseListener(event -> {
        if (event instanceof LeaseEvent.Restored restored) {
          System.out.println("Healed: " + restored.getOldLeaseId() + " -> " + restored.getNewLeaseId());
        } else if (event instanceof LeaseEvent.Failed) {
          // Healing gave up. The key is gone and stays gone.
          System.out.println("Healing abandoned");
        } else {
          System.out.println("Lease event: " + event);
        }
      });
      kv.start();
    }
    // --8<-- [end:lease-listener]
  }
}
