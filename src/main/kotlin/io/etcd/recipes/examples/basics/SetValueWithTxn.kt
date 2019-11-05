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

package io.etcd.recipes.examples.basics

import io.etcd.jetcd.KV
import io.etcd.recipes.common.delete
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction

fun main() {
    val urls = listOf("http://localhost:2379")
    val path = "/txnexample"
    val keyval = "debug"

    fun checkForKey(kvClient: KV) {
        kvClient.transaction {
            If(path.doesExist)
            Then(keyval setTo "Key $path found")
            Else(keyval setTo "Key $path not found")
        }

        println("Debug value: ${kvClient.getValue(keyval, "not_used")}")
    }

    etcdExec(urls) { _, kvClient ->
        println("Deleting keys")
        kvClient.delete(path, keyval)

        println("Key present: ${kvClient.isKeyPresent(keyval)}")
        checkForKey(kvClient)
        println("Key present: ${kvClient.isKeyPresent(keyval)}")
        kvClient.putValue(path, "Something")
        checkForKey(kvClient)
    }
}