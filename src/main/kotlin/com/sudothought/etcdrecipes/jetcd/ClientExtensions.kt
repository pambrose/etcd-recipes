/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package com.sudothought.etcdrecipes.jetcd

import io.etcd.jetcd.Auth
import io.etcd.jetcd.Client
import io.etcd.jetcd.Cluster
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Lock
import io.etcd.jetcd.Maintenance
import io.etcd.jetcd.Watch

fun Client.withWatchClient(block: (watchClient: Watch) -> Unit) = watchClient.use { block(it) }

fun Client.withLeaseClient(block: (leaseClient: Lease) -> Unit) = leaseClient.use { block(it) }

fun Client.withLockClient(block: (lockClient: Lock) -> Unit) = lockClient.use { block(it) }

fun Client.withMaintClient(block: (maintClient: Maintenance) -> Unit) = maintenanceClient.use { block(it) }

fun Client.withClusterClient(block: (clusterClient: Cluster) -> Unit) = clusterClient.use { block(it) }

fun Client.withAuthrClient(block: (authClient: Auth) -> Unit) = authClient.use { block(it) }

fun Client.withKvClient(block: (kvClient: KV) -> Unit) = kvClient.use { block(it) }