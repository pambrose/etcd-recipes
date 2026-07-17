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

package website.counter;

import io.etcd.jetcd.Client;
import io.etcd.recipes.counter.DistributedAtomicLong;

public class CounterSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    try (DistributedAtomicLong counter = new DistributedAtomicLong(client, "/counters/orders")) {
      // Optional: start() is invoked automatically on first use. Call it explicitly
      // when you want the create-if-absent round trip to happen at a point you chose.
      counter.start();

      System.out.println("Current: " + counter.get());
    }
    // --8<-- [end:basic]
  }

  public void mutators(Client client) {
    // --8<-- [start:mutators]
    try (DistributedAtomicLong counter = new DistributedAtomicLong(client, "/counters/orders")) {
      // Each mutator returns the value THIS call committed, not a re-read. By the time
      // you look at it another client may already have moved the counter past it.
      long afterIncrement = counter.increment();
      long afterDecrement = counter.decrement();
      long afterAdd = counter.add(10L);
      long afterSubtract = counter.subtract(4L);

      System.out.println(afterIncrement + " " + afterDecrement + " " + afterAdd + " " + afterSubtract);

      // get() is a plain read: true at some recent revision, possibly stale on the
      // next line. Never branch on it to decide whether a mutation will succeed.
      System.out.println("Now: " + counter.get());
    }
    // --8<-- [end:mutators]
  }

  public void defaultValue(Client client) {
    // --8<-- [start:default]
    // Java has no named arguments: the third positional parameter is `default`, and
    // it is honoured only by whichever client creates the key first.
    try (DistributedAtomicLong counter = new DistributedAtomicLong(client, "/counters/seats", 100L)) {
      System.out.println("Seats left: " + counter.decrement());
    }
    // --8<-- [end:default]
  }

  public void delete(Client client) {
    // --8<-- [start:delete]
    // Static, and deliberately not an instance method: removing the key out from under
    // live instances is a destructive act, not a step in a counter's lifecycle.
    DistributedAtomicLong.delete(client, "/counters/orders");
    // --8<-- [end:delete]
  }
}
