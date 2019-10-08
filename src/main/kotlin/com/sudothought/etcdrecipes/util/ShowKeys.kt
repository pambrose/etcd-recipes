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

package com.sudothought.etcdrecipes.util

import com.sudothought.common.util.sleep
import com.sudothought.etcdrecipes.jetcd.count
import com.sudothought.etcdrecipes.jetcd.getKeyValues
import com.sudothought.etcdrecipes.jetcd.valuesAsString
import com.sudothought.etcdrecipes.jetcd.withKvClient
import io.etcd.jetcd.Client
import kotlin.time.seconds

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/"

    Client.builder().endpoints(*urls.toTypedArray()).build()
        .use { client ->
            client.withKvClient { kvClient ->
                kvClient.apply {
                    repeat(600) {
                        println(getKeyValues(path).valuesAsString)
                        println(count(path))
                        sleep(1.seconds)
                    }
                }
            }
        }
}