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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.examples.basics

import io.etcd.jetcd.Client
import io.etcd.recipes.common.connectToEtcd
import io.etcd.recipes.common.deleteKeys
import io.etcd.recipes.common.doesExist
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.isKeyPresent
import io.etcd.recipes.common.putValue
import io.etcd.recipes.common.setTo
import io.etcd.recipes.common.transaction

fun main() {
  val urls = listOf("http://localhost:2379")
  val path = "/txnexample"
  val keyval = "debug"

  fun checkForKey(client: Client) {
    client.transaction {
      If(path.doesExist)
      Then(keyval setTo "Key $path found")
      Else(keyval setTo "Key $path not found")
    }

    println("Debug value: ${client.getValue(keyval, "not_used")}")
  }

  connectToEtcd(urls) { client ->
    println("Deleting keys")
    client.deleteKeys(path, keyval)

    println("Key present: ${client.isKeyPresent(keyval)}")
    checkForKey(client)
    println("Key present: ${client.isKeyPresent(keyval)}")
    client.putValue(path, "Something")
    checkForKey(client)
  }
}
