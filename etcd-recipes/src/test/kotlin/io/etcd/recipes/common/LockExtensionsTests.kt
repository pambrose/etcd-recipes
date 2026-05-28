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

package io.etcd.recipes.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlin.time.Duration.Companion.seconds

class LockExtensionsTests : StringSpec() {
    private val path = "/lock-extensions/${javaClass.simpleName}/lock"

    init {
        "lock returns an owned key under the requested name and unlock releases it" {
            connectToEtcd(urls) { client ->
                val lease = client.leaseGrant(10.seconds)
                try {
                    val response = client.lock(path, lease.id)
                    // etcd returns a unique owned key prefixed by the lock name.
                    response.key.asString shouldStartWith path

                    // Releasing via the owned key must succeed.
                    client.unlock(response.key.asString)
                } finally {
                    client.leaseRevoke(lease)
                }
            }
        }

        "a second lock on the same name is granted after the first is released" {
            connectToEtcd(urls) { client ->
                val lease1 = client.leaseGrant(10.seconds)
                val lease2 = client.leaseGrant(10.seconds)
                try {
                    val first = client.lock(path, lease1.id)
                    client.unlock(first.key.asString)

                    // Now the name is free, so a fresh acquire returns promptly.
                    val second = client.lock(path, lease2.id)
                    second.key.asString shouldStartWith path
                    client.unlock(second.key.asString)
                } finally {
                    client.leaseRevoke(lease1)
                    client.leaseRevoke(lease2)
                }
            }
        }
    }
}
