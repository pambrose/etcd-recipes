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

fun compactOption(block: CompactOption.Builder.() -> CompactOption.Builder): CompactOption =
    CompactOption.newBuilder().block().build()

fun deleteOption(block: DeleteOption.Builder.() -> DeleteOption.Builder): DeleteOption =
    DeleteOption.newBuilder().block().build()

fun getOption(block: GetOption.Builder.() -> GetOption.Builder): GetOption = GetOption.newBuilder().block().build()

fun leaseOption(block: LeaseOption.Builder.() -> LeaseOption.Builder): LeaseOption =
    LeaseOption.newBuilder().block().build()

fun putOption(block: PutOption.Builder.() -> PutOption.Builder): PutOption = PutOption.newBuilder().block().build()

fun watchOption(block: WatchOption.Builder.() -> WatchOption.Builder): WatchOption =
    WatchOption.newBuilder().block().build()