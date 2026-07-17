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

package website.discovery;

import io.etcd.jetcd.Client;
import io.etcd.recipes.common.EtcdRecipeException;
import io.etcd.recipes.common.StringCodec;
import io.etcd.recipes.discovery.RoundRobinStrategy;
import io.etcd.recipes.discovery.ServiceCache;
import io.etcd.recipes.discovery.ServiceDiscovery;
import io.etcd.recipes.discovery.ServiceInstance;
import io.etcd.recipes.discovery.ServiceProvider;
import io.etcd.recipes.discovery.ServiceRegistry;
import io.etcd.recipes.discovery.ServiceType;
import io.etcd.recipes.discovery.TypedServiceInstances;

import java.util.List;

public class DiscoverySnippets {

  public void register(Client client) throws EtcdRecipeException {
    // --8<-- [start:register]
    try (ServiceDiscovery discovery = new ServiceDiscovery(client, "/services/orders")) {
      ServiceInstance instance = ServiceInstance.newBuilder("worker", "{\"weight\":5}").build();
      instance.setAddress("10.0.0.7");
      instance.setPort(8080);

      // Writes /services/orders/names/worker/<id>, bound to a self-healing lease.
      discovery.registerService(instance);

      // Mutate the instance, then push the new JSON to the same key.
      instance.setPort(8081);
      discovery.updateService(instance);

      // Removes the key now rather than leaving it to expire at the TTL.
      discovery.unregisterService(instance);
    }
    // --8<-- [end:register]
  }

  public void builder() {
    // --8<-- [start:builder]
    // The builder's setters return void, so there is no fluent chain to follow —
    // set the fields, then build().
    ServiceInstance.Companion.ServiceInstanceBuilder builder =
      ServiceInstance.newBuilder("worker", "{\"weight\":5}");
    builder.setAddress("10.0.0.7");
    builder.setPort(8080);
    builder.setSslPort(8443);
    builder.setUri("https://10.0.0.7:8443");
    builder.setServiceType(ServiceType.DYNAMIC);
    builder.setEnabled(true);

    ServiceInstance instance = builder.build();

    // id is random and assigned at construction. It is the last segment of the
    // instance's etcd key, and what queryForInstance(name, id) takes.
    System.out.println(instance.getName() + "/" + instance.getId());
    System.out.println("isDynamic: " + instance.getServiceType().isDynamic());

    // toJson()/toObject() are the wire format the recipes store and read.
    ServiceInstance parsed = ServiceInstance.toObject(instance.toJson());
    System.out.println("Round-tripped " + parsed.getName() + " on port " + parsed.getPort());
    // --8<-- [end:builder]
  }

  public void typed() {
    // --8<-- [start:typed]
    // jsonPayload is a String, so the codec must be a UTF-8 text codec. jsonCodec()
    // is `inline reified` and therefore Kotlin-only; from Java use StringCodec, or
    // the Jackson module's JSON codec.
    ServiceInstance instance =
      TypedServiceInstances.serviceInstance("worker", "{\"weight\":5}", StringCodec.INSTANCE);

    String payload = TypedServiceInstances.payload(instance, StringCodec.INSTANCE);
    System.out.println("Payload: " + payload);

    // Re-encodes into jsonPayload in place; follow it with updateService().
    TypedServiceInstances.setPayload(instance, "{\"weight\":9}", StringCodec.INSTANCE);
    // --8<-- [end:typed]
  }

  public void query(Client client) throws EtcdRecipeException {
    // --8<-- [start:query]
    try (ServiceDiscovery discovery = new ServiceDiscovery(client, "/services/orders")) {
      // One-shot reads: every call is a range GET, not a cached lookup.
      List<String> names = discovery.queryForNames();
      List<ServiceInstance> instances = discovery.queryForInstances("worker");

      System.out.println("Services: " + names + ", worker instances: " + instances.size());

      // Throws EtcdRecipeException if that exact instance key is gone.
      if (!instances.isEmpty()) {
        ServiceInstance one = discovery.queryForInstance("worker", instances.get(0).getId());
        System.out.println("Found " + one.getName() + "/" + one.getId());
      }
    }
    // --8<-- [end:query]
  }

  public void cache(Client client) {
    // --8<-- [start:cache]
    // withServiceCache is a Kotlin inline extension; Java closes the cache itself.
    // Note the path is <servicePath>/names — what ServiceDiscovery hands its
    // caches internally.
    try (ServiceCache cache = new ServiceCache(client, "/services/orders/names", "worker")) {
      // Register listeners BEFORE start(): the snapshot taken by start() does not
      // replay through them, and instances registered after it do.
      cache.addListenerForChanges((eventType, isAdd, serviceName, serviceInstance) ->
        System.out.println(eventType + " isAdd=" + isAdd + " " + serviceName + " -> " + serviceInstance));

      cache.addRecoveryListener(event ->
        System.out.println("Watch recovery: " + event));

      // One-shot: a second start() throws.
      cache.start();

      // In-memory read of the watch-maintained set; no round trip.
      System.out.println(cache.getInstances().size() + " instances of worker");
    }
    // --8<-- [end:cache]
  }

  public void registry(Client client) throws EtcdRecipeException {
    // --8<-- [start:registry]
    // Write side only. Registration leases self-heal: if one expires, the healer
    // grants a new lease and re-runs the registration CAS.
    try (ServiceRegistry registry = new ServiceRegistry(client, "/services/orders", 5L)) {
      registry.addLeaseListener(event ->
        System.out.println("Lease event: " + event));

      ServiceInstance instance = ServiceInstance.newBuilder("worker", "{\"weight\":5}").build();
      registry.registerService(instance);
    }
    // --8<-- [end:registry]
  }

  public void provider(Client client) throws EtcdRecipeException {
    // --8<-- [start:provider]
    try (ServiceDiscovery discovery = new ServiceDiscovery(client, "/services/orders");
         ServiceProvider provider = discovery.serviceProvider("worker")) {
      // start() opens an owned, watch-backed ServiceCache, so every later read is
      // in-memory. Skip it and each read is a direct range GET instead.
      provider.start();

      // RandomStrategy by default. Throws EtcdRecipeException when nothing is
      // available — no instances registered, or all of them ejected.
      ServiceInstance instance = provider.getInstance();
      System.out.println("Selected " + instance.getAddress() + ":" + instance.getPort());

      System.out.println(provider.getAllInstances().size() + " instances registered");
    }
    // --8<-- [end:provider]
  }

  public void noteError(Client client) throws EtcdRecipeException {
    // --8<-- [start:note-error]
    try (ServiceDiscovery discovery = new ServiceDiscovery(client, "/services/orders");
         // A fresh RoundRobinStrategy for THIS provider: it carries a cursor, and
         // sharing one across providers interleaves their rotations.
         ServiceProvider provider = discovery.serviceProvider("worker", new RoundRobinStrategy(), 3)) {
      provider.start();

      ServiceInstance instance = provider.getInstance();
      if (!sendRequest(instance)) {
        // Pass back the SAME instance, unmodified: ejection is keyed on the
        // instance's value, so a mutated copy silently records nothing useful.
        // After errorThreshold errors it drops out of selection for downPeriod,
        // then rejoins automatically.
        provider.noteError(instance);
      }
    }
    // --8<-- [end:note-error]
  }

  // Stands in for whatever RPC you make against the chosen instance.
  private boolean sendRequest(ServiceInstance instance) {
    return instance.getEnabled();
  }
}
