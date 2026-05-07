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

@file:JvmName("BuilderUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.ClientBuilder
import io.etcd.jetcd.options.CompactOption
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.LeaseOption
import io.etcd.jetcd.options.PutOption
import io.etcd.jetcd.options.WatchOption

fun etcdClient(block: ClientBuilder.() -> ClientBuilder): Client = Client.builder().block().build()

fun compactOption(receiver: CompactOption.Builder.() -> CompactOption.Builder): CompactOption =
  CompactOption.builder().receiver().build()

fun deleteOption(receiver: DeleteOption.Builder.() -> DeleteOption.Builder): DeleteOption =
  DeleteOption.builder().receiver().build()

fun getOption(receiver: GetOption.Builder.() -> GetOption.Builder): GetOption =
  GetOption.builder().receiver().build()

fun leaseOption(receiver: LeaseOption.Builder.() -> LeaseOption.Builder): LeaseOption =
  LeaseOption.builder().receiver().build()

fun putOption(receiver: PutOption.Builder.() -> PutOption.Builder): PutOption =
  PutOption.builder().receiver().build()

fun watchOption(receiver: WatchOption.Builder.() -> WatchOption.Builder): WatchOption =
  WatchOption.builder().receiver().build()
