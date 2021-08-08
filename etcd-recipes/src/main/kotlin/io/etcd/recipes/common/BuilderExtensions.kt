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

@file:JvmName("BuilderUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.Client
import io.etcd.jetcd.ClientBuilder
import io.etcd.jetcd.options.*

fun etcdClient(block: ClientBuilder.() -> ClientBuilder): Client = Client.builder().block().build()

fun compactOption(reciever: CompactOption.Builder.() -> CompactOption.Builder): CompactOption =
  CompactOption.newBuilder().reciever().build()

fun deleteOption(reciever: DeleteOption.Builder.() -> DeleteOption.Builder): DeleteOption =
  DeleteOption.newBuilder().reciever().build()

fun getOption(reciever: GetOption.Builder.() -> GetOption.Builder): GetOption =
  GetOption.newBuilder().reciever().build()

fun leaseOption(reciever: LeaseOption.Builder.() -> LeaseOption.Builder): LeaseOption =
  LeaseOption.newBuilder().reciever().build()

fun putOption(reciever: PutOption.Builder.() -> PutOption.Builder): PutOption =
  PutOption.newBuilder().reciever().build()

fun watchOption(reciever: WatchOption.Builder.() -> WatchOption.Builder): WatchOption =
  WatchOption.newBuilder().reciever().build()