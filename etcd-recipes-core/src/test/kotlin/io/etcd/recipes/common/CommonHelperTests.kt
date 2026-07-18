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

import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.CompactOption
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.LeaseOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for the pure conversion / builder helpers in the `common` package.
 * None of these touch etcd, so they run fast and deterministically.
 */
class CommonHelperTests : StringSpec() {
    init {
        // --- ByteSequenceUtils round-trips ---
        "Int round-trips through asByteSequence/asInt" {
            42.asByteSequence.asInt shouldBe 42
            0.asByteSequence.asInt shouldBe 0
            (-7).asByteSequence.asInt shouldBe -7
            Int.MAX_VALUE.asByteSequence.asInt shouldBe Int.MAX_VALUE
        }

        "Long round-trips through asByteSequence/asLong" {
            42L.asByteSequence.asLong shouldBe 42L
            Long.MIN_VALUE.asByteSequence.asLong shouldBe Long.MIN_VALUE
        }

        "String round-trips through asByteSequence/asString" {
            "hello".asByteSequence.asString shouldBe "hello"
            "".asByteSequence.asString shouldBe ""
        }

        // --- PairUtils ---
        "Pair<String, ByteSequence> converts to typed pairs" {
            ("k" to 99.asByteSequence).asInt shouldBe ("k" to 99)
            ("k" to 99L.asByteSequence).asLong shouldBe ("k" to 99L)
            ("k" to "v".asByteSequence).asString shouldBe ("k" to "v")
        }

        "List<Pair<String, ByteSequence>> converts and exposes keys/values" {
            val list = ["a" to 1.asByteSequence, "b" to 2.asByteSequence]
            list.asInt shouldBe ["a" to 1, "b" to 2]
            list.asInt.keys shouldBe ["a", "b"]
            list.asInt.values shouldBe [1, 2]

            ["a" to 1L.asByteSequence].asLong shouldBe ["a" to 1L]
            ["a" to "x".asByteSequence].asString shouldBe ["a" to "x"]
        }

        // --- BuilderUtils option DSLs ---
        "option builder DSLs produce the corresponding option objects" {
            deleteOption { isPrefix(true) }.shouldBeInstanceOf<DeleteOption>()
            compactOption { withCompactPhysical(true) }.shouldBeInstanceOf<CompactOption>()
            leaseOption { withAttachedKeys() }.shouldBeInstanceOf<LeaseOption>()
        }

        // --- TxnUtils comparison + op builders ---
        "comparison helpers build Cmp instances for every value overload" {
            equalTo("v", CmpTarget.value("v".asByteSequence)).shouldBeInstanceOf<Cmp>()
            lessThan(1, CmpTarget.version(1)).shouldBeInstanceOf<Cmp>()
            greaterThan(1L, CmpTarget.version(1)).shouldBeInstanceOf<Cmp>()
            "key".doesExist.shouldBeInstanceOf<Cmp>()
            "key".doesNotExist.shouldBeInstanceOf<Cmp>()
        }

        "setTo and deleteOp build the corresponding Op instances" {
            ("key" setTo "value").shouldBeInstanceOf<Op.PutOp>()
            ("key" setTo 1).shouldBeInstanceOf<Op.PutOp>()
            ("key" setTo 1L).shouldBeInstanceOf<Op.PutOp>()
            deleteOp("key").shouldBeInstanceOf<Op.DeleteOp>()
        }
    }
}
