/*
 * Copyright © 2021 Paul Ambrose (pambrose@mac.com)
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

package io.etcd.recipes.examples.discovery;

import com.google.common.collect.Lists;
import io.etcd.jetcd.Client;
import io.etcd.recipes.common.EtcdRecipeException;
import io.etcd.recipes.discovery.ServiceDiscovery;
import io.etcd.recipes.discovery.ServiceInstance;

import java.util.List;

import static com.github.pambrose.common.util.MiscJavaFuncs.sleepSecs;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;

public class ServiceDiscoveryExample {

  public static final List<String> urls = Lists.newArrayList("http://localhost:2379");
  public static final String path = "/services/ServiceDiscoveryExample";
  public static final String serviceName = "ExampleService";

  public static void main(String[] args) throws EtcdRecipeException {
    serviceExample(true);
  }

  public static void serviceExample(boolean verbose) throws EtcdRecipeException {

    try (Client client = connectToEtcd(urls);
         ServiceDiscovery sd = new ServiceDiscovery(client, path)) {

      IntPayload payload = new IntPayload(-999);
      ServiceInstance service = ServiceInstance.newBuilder(serviceName, payload.toJson()).build();

      System.out.println(service.toJson());

      System.out.println("Registering service");
      sd.registerService(service);
      if (verbose) {
        System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));
        System.out.println("Retrieved values: " + sd.queryForInstances(service.getName()));
        System.out.println("Retrieved names: " + sd.queryForNames());
      }

      sleepSecs(2);
      System.out.println("Updating service");
      payload.setIntval(-888);
      service.setJsonPayload(payload.toJson());
      sd.updateService(service);
      if (verbose) {
        System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));
        System.out.println("Retrieved values: " + sd.queryForInstances(service.getName()));
        System.out.println("Retrieved names: " + sd.queryForNames());
      }

      sleepSecs(2);
      System.out.println("Unregistering service");
      sd.unregisterService(service);
      sleepSecs(3);

      try {
        System.out.println("Retrieved value: " + sd.queryForInstance(service.getName(), service.getId()));
      } catch (EtcdRecipeException e) {
        if (verbose) {
          System.out.println("Exception: " + e);
        }
      }
    }
  }
}
