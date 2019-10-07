/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package org.athenian.jetcd

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KV
import io.etcd.jetcd.Txn
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.DeleteOption
import io.etcd.jetcd.options.PutOption

fun <T> equals(keyname: String, target: CmpTarget<T>): Cmp = Cmp(keyname.asByteSequence, Cmp.Op.EQUAL, target)

fun <T> less(keyname: String, target: CmpTarget<T>): Cmp = Cmp(keyname.asByteSequence, Cmp.Op.LESS, target)

fun <T> greater(keyname: String, target: CmpTarget<T>): Cmp = Cmp(keyname.asByteSequence, Cmp.Op.GREATER, target)

fun Lazy<KV>.transaction(block: Txn.() -> Txn): TxnResponse = value.transaction(block)

fun KV.transaction(block: Txn.() -> Txn): TxnResponse =
    txn().run {
        block()
        commit()
    }.get()

fun deleteOp(keyname: String, option: DeleteOption = DeleteOption.DEFAULT): Op.DeleteOp =
    Op.delete(keyname.asByteSequence, option)

fun putOp(keyname: String, keyval: String, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    putOp(keyname, keyval.asByteSequence, option)

fun putOp(keyname: String, keyval: Int, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    putOp(keyname, keyval.asByteSequence, option)

fun putOp(keyname: String, keyval: Long, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    putOp(keyname, keyval.asByteSequence, option)

fun putOp(keyname: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    Op.put(keyname.asByteSequence, keyval, option)