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

@file:JvmName("TxnUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KV
import io.etcd.jetcd.Txn
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.PutOption

fun KV.transaction(block: Txn.() -> Txn): TxnResponse =
    txn().run {
        block()
        commit()
    }.get()

fun Lazy<KV>.transaction(block: Txn.() -> Txn): TxnResponse = value.transaction(block)

fun <T> equalTo(key: ByteSequence, target: CmpTarget<T>): Cmp = Cmp(key, Cmp.Op.EQUAL, target)
fun <T> lessThan(key: ByteSequence, target: CmpTarget<T>): Cmp = Cmp(key, Cmp.Op.LESS, target)
fun <T> greaterThan(key: ByteSequence, target: CmpTarget<T>): Cmp = Cmp(key, Cmp.Op.GREATER, target)

fun <T> equalTo(keyName: String, target: CmpTarget<T>): Cmp = equalTo(keyName.asByteSequence, target)
fun <T> lessThan(keyName: String, target: CmpTarget<T>): Cmp = lessThan(keyName.asByteSequence, target)
fun <T> greaterThan(keyName: String, target: CmpTarget<T>): Cmp = greaterThan(keyName.asByteSequence, target)

val String.doesNotExist: Cmp get() = equalTo(this, CmpTarget.version(0))
val String.doesExist: Cmp get() = greaterThan(this, CmpTarget.version(0))

@JvmOverloads
fun deleteKey(key: ByteSequence, option: DeleteOption = DeleteOption.DEFAULT): Op.DeleteOp = Op.delete(key, option)

@JvmOverloads
fun deleteKey(keyName: String, option: DeleteOption = DeleteOption.DEFAULT): Op.DeleteOp =
    deleteKey(keyName.asByteSequence, option)

fun String.setTo(value: ByteSequence, putOption: PutOption): Op.PutOp =
    Op.put(asByteSequence, value, putOption)

fun String.setTo(keyval: String, putOption: PutOption): Op.PutOp =
    Op.put(asByteSequence, keyval.asByteSequence, putOption)

fun String.setTo(keyval: Int, putOption: PutOption): Op.PutOp =
    Op.put(asByteSequence, keyval.asByteSequence, putOption)

fun String.setTo(keyval: Long, putOption: PutOption): Op.PutOp =
    Op.put(asByteSequence, keyval.asByteSequence, putOption)

infix fun String.setTo(value: ByteSequence): Op.PutOp = setTo(value, PutOption.DEFAULT)
infix fun String.setTo(keyval: String): Op.PutOp = setTo(keyval, PutOption.DEFAULT)
infix fun String.setTo(keyval: Int): Op.PutOp = setTo(keyval, PutOption.DEFAULT)
infix fun String.setTo(keyval: Long): Op.PutOp = setTo(keyval, PutOption.DEFAULT)