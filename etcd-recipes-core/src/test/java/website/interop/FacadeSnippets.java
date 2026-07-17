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

package website.interop;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.recipes.common.EtcdRecipes;
import io.etcd.recipes.lock.DistributedMutex;
import io.etcd.recipes.lock.EtcdLock;
import io.etcd.recipes.lock.EtcdLockKt;
import kotlin.Unit;

import java.util.List;

import static io.etcd.recipes.common.ByteSequenceUtils.getAsString;
import static io.etcd.recipes.common.ChildrenUtils.getChildCount;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.getValue;
import static io.etcd.recipes.common.KVUtils.isKeyPresent;
import static io.etcd.recipes.common.KVUtils.putValue;

public class FacadeSnippets {

  public void facades() {
    // --8<-- [start:facades]
    // A Kotlin extension `fun Client.putValue(...)` in a file annotated
    // @file:JvmName("KVUtils") becomes a static KVUtils.putValue(client, ...).
    // The receiver simply becomes the first parameter.
    try (Client client = connectToEtcd(List.of("http://localhost:2379"))) {
      putValue(client, "/config/mode", "live");

      String mode = getValue(client, "/config/mode", "default");
      boolean present = isKeyPresent(client, "/config/mode");
      long children = getChildCount(client, "/config");

      System.out.printf("%s present=%s children=%d%n", mode, present, children);
    }
    // --8<-- [end:facades]
  }

  public void byteSequences() {
    // --8<-- [start:bytes]
    ByteSequence bytes = ByteSequence.from("hello".getBytes());
    // Kotlin's `val ByteSequence.asString` is an extension *property*, so Java
    // sees the getter name: getAsString(...), not asString(...).
    String text = getAsString(bytes);
    System.out.println(text);
    // --8<-- [end:bytes]
  }

  public void overloadLadder(Client client) {
    // --8<-- [start:overloads]
    // @JvmOverloads generates a ladder of constructors, so Java can stop at any
    // trailing default. But Java has no named arguments: to reach a later
    // parameter you must supply every earlier one positionally.
    DistributedMutex a = new DistributedMutex(client, "/locks/orders");
    DistributedMutex b = new DistributedMutex(client, "/locks/orders", 10L);
    // --8<-- [end:overloads]
    a.close();
    b.close();
  }

  public void withLockFromJava(Client client) throws InterruptedException {
    // --8<-- [start:with-lock]
    try (DistributedMutex mutex = new DistributedMutex(client, "/locks/orders")) {
      // Possible, but not worth it: the Kotlin `withLock { }` becomes a static
      // taking a Function0, so a void body has to return Unit.INSTANCE.
      EtcdLockKt.withLock((EtcdLock) mutex, () -> {
        System.out.println("Critical section");
        return Unit.INSTANCE;
      });

      // Prefer plain try/finally in Java. It reads better and does the same thing.
      mutex.lock();
      try {
        System.out.println("Critical section");
      } finally {
        mutex.unlock();
      }
    }
    // --8<-- [end:with-lock]
  }

  public void facade(Client client) {
    // --8<-- [start:recipes-facade]
    // EtcdRecipes is a plain class, so it reads naturally from Java.
    EtcdRecipes recipes = new EtcdRecipes(client);
    try (DistributedMutex mutex = recipes.mutex("/locks/orders")) {
      System.out.println("Got a mutex from the facade: " + mutex.getLockPath());
    }
    // --8<-- [end:recipes-facade]
  }
}
