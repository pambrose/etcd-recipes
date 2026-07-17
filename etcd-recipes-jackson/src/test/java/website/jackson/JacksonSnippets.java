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

package website.jackson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.options.PutOption;
import io.etcd.recipes.common.EtcdCodec;
import io.etcd.recipes.common.RpcResilience;
import io.etcd.recipes.common.TypedKVUtils;
import io.etcd.recipes.jackson.JacksonCodec;
import io.etcd.recipes.queue.TypedDistributedQueue;

import java.util.List;

public class JacksonSnippets {

  // --8<-- [start:pojo]
  // A record is decodable by a vanilla ObjectMapper out of the box, as is any bean with a
  // no-arg constructor and setters.
  public record Order(String id, int qty) {
  }
  // --8<-- [end:pojo]

  public void codec() {
    // --8<-- [start:codec]
    // The Class token form. The second constructor argument is the ObjectMapper; omit it
    // and you get a vanilla one.
    EtcdCodec<Order> codec = new JacksonCodec<>(Order.class);

    // Supply your own mapper to control the wire format.
    ObjectMapper mapper = new ObjectMapper();
    EtcdCodec<Order> configured = new JacksonCodec<>(Order.class, mapper);
    // --8<-- [end:codec]
    System.out.println(codec + " " + configured);
  }

  public void generic() {
    // --8<-- [start:generic]
    // Order.class cannot express List<Order> — the type argument is erased. A
    // TypeReference captures it, so generic payloads round-trip correctly.
    EtcdCodec<List<Order>> codec = new JacksonCodec<>(new TypeReference<List<Order>>() {
    });
    // --8<-- [end:generic]
    System.out.println(codec);
  }

  public void typedKeyValue(Client client) {
    // --8<-- [start:typed-kv]
    EtcdCodec<Order> codec = new JacksonCodec<>(Order.class);

    // The typed KV helpers are Kotlin extension functions, so from Java they are statics on
    // TypedKVUtils with the client as the first argument. They have no @JvmOverloads, so
    // the trailing defaults must be passed explicitly.
    TypedKVUtils.putValue(client, "/config/order", new Order("A-1", 3), codec,
      PutOption.DEFAULT, RpcResilience.DEFAULT);

    Order order = TypedKVUtils.getValue(client, "/config/order", codec, RpcResilience.DEFAULT);
    System.out.println("Read back " + order);
    // --8<-- [end:typed-kv]
  }

  public void typedQueue(Client client) throws InterruptedException {
    // --8<-- [start:typed-queue]
    EtcdCodec<Order> codec = new JacksonCodec<>(Order.class);

    try (TypedDistributedQueue<Order> queue =
           new TypedDistributedQueue<>(client, "/queues/orders", codec)) {
      queue.enqueue(new Order("A-1", 3));

      // dequeue() hands back an Order, not a ByteSequence.
      Order order = queue.dequeue();
      System.out.println("Dequeued " + order);
    }
    // --8<-- [end:typed-queue]
  }
}
