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

@file:JvmName("LeaseUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.CloseableClient
import io.etcd.jetcd.Observers
import io.etcd.jetcd.lease.LeaseGrantResponse
import kotlin.time.Duration

fun Client.keepAliveWith(lease: LeaseGrantResponse, block: () -> Unit) =
    keepAlive(lease)
        .use {
            block.invoke()
        }

fun Client.keepAlive(lease: LeaseGrantResponse): CloseableClient =
    leaseClient.keepAlive(lease.id,
                          Observers.observer(
                              { /*println("KeepAlive next resp: $next")*/ },
                              { /*println("KeepAlive err resp: $err")*/ }))

fun Client.leaseGrant(ttl: Duration): LeaseGrantResponse = leaseClient.grant(ttl.inSeconds.toLong()).get()

