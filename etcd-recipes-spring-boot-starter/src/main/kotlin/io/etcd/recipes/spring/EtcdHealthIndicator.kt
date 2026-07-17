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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.spring

import io.etcd.jetcd.Client
import io.etcd.recipes.common.ping
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * Spring Boot Actuator health for etcd: an active [Client.ping] (bounded count-only GET) maps to
 * `UP` / `DOWN`. Only contributed when Actuator is on the classpath.
 */
class EtcdHealthIndicator(
  private val client: Client,
) : HealthIndicator {
  override fun health(): Health = if (client.ping()) Health.up().build() else Health.down().build()
}
