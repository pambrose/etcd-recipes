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

package website.gettingstarted;

import io.etcd.jetcd.Client;
import io.etcd.recipes.common.EtcdConnectionConfig;
import io.etcd.recipes.common.EtcdTlsConfig;

import java.time.Duration;
import java.util.List;

import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.ClientUtils.ping;
import static io.etcd.recipes.common.KVUtils.getValue;
import static io.etcd.recipes.common.KVUtils.putValue;

public class ConnectSnippets {

  public void basic() {
    // --8<-- [start:basic]
    // The Kotlin extensions are reached through their @JvmName facades:
    // ClientUtils.connectToEtcd, KVUtils.putValue, and so on.
    try (Client client = connectToEtcd(List.of("http://localhost:2379"))) {
      putValue(client, "/greeting", "hello");
      System.out.println(getValue(client, "/greeting", "<absent>"));
    }
    // --8<-- [end:basic]
  }

  public void config() {
    // --8<-- [start:config]
    EtcdTlsConfig tls =
      new EtcdTlsConfig("/etc/etcd/ca.crt", "/etc/etcd/client.crt", "/etc/etcd/client.key");

    EtcdConnectionConfig config =
      new EtcdConnectionConfig(
        List.of("https://etcd-1:2379", "https://etcd-2:2379"),
        "app",                              // user
        System.getenv("ETCD_PASSWORD"),     // password
        "/prod",                            // namespace
        Duration.ofSeconds(5),              // connectTimeout (java.time.Duration)
        Duration.ofSeconds(30),             // retryMaxDuration
        tls);

    try (Client client = connectToEtcd(config)) {
      System.out.println("Reachable: " + ping(client));
    }
    // --8<-- [end:config]
  }

  public void pingEtcd() {
    // --8<-- [start:ping]
    try (Client client = connectToEtcd(List.of("http://localhost:2379"))) {
      // An active, count-only GET. Cheap enough for a health endpoint.
      if (!ping(client)) {
        System.err.println("etcd is not reachable");
      }
    }
    // --8<-- [end:ping]
  }
}
