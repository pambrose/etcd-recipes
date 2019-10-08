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

package com.sudothought.etcdrecipes.basics

import com.sudothought.etcdrecipes.jetcd.*
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.op.CmpTarget

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/txntest"
    val debug = "/debug"

    fun checkForKey(kvClient: KV) {
        kvClient.transaction {
            If(equalTo(path, CmpTarget.version(0)))
            Then(putOp(debug, "Key $path not found"))
            Else(putOp(debug, "Key $path found"))
        }

        println("Debug value: ${kvClient.getStringValue(debug, "unset")}")
    }

    Client.builder().endpoints(*urls.toTypedArray()).build()
        .use { client ->
            client.withKvClient { kvClient ->
                println("Deleting keys")
                kvClient.delete(path, debug)

                checkForKey(kvClient)
                kvClient.putValue(path, "Something")
                checkForKey(kvClient)
            }
        }
}