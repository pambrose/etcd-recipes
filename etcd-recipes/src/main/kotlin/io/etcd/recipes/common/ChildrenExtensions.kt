/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

@file:JvmName("ChildrenUtils")
@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.common

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder

@JvmOverloads
fun Client.getChildren(keyName: String,
                       target: GetOption.SortTarget = GetOption.SortTarget.KEY,
                       order: SortOrder = SortOrder.ASCEND,
                       keysOnly: Boolean = false): List<Pair<String, ByteSequence>> {
    val trailingKey = keyName.ensureSuffix("/")
    val getOption =
        getOption {
            withPrefix(trailingKey)
            withSortField(target)
            withSortOrder(order)
            withKeysOnly(keysOnly)
        }
    return getKeyValuePairs(trailingKey, getOption)
}

fun Client.getFirstChild(keyName: String, target: GetOption.SortTarget): GetResponse =
    getSingleChild(keyName, target, SortOrder.ASCEND)

fun Client.getLastChild(keyName: String, target: GetOption.SortTarget): GetResponse =
    getSingleChild(keyName, target, SortOrder.DESCEND)

fun GetOption.Builder.withPrefix(prefix: String): GetOption.Builder = withPrefix(prefix.asByteSequence)

private fun Client.getSingleChild(keyName: String,
                                  target: GetOption.SortTarget,
                                  order: SortOrder): GetResponse {
    val trailingKey = keyName.ensureSuffix("/")
    val getOption =
        getOption {
            withPrefix(trailingKey)
            withSortField(target)
            withSortOrder(order)
            withLimit(1)
        }
    return getResponse(trailingKey, getOption)
}

@JvmOverloads
fun Client.getChildrenKeys(keyName: String,
                           target: GetOption.SortTarget = GetOption.SortTarget.KEY,
                           order: SortOrder = SortOrder.ASCEND): List<String> =
    getChildren(keyName, target, order, true).keys

@JvmOverloads
fun Client.getChildrenValues(keyName: String,
                             target: GetOption.SortTarget = GetOption.SortTarget.KEY,
                             order: SortOrder = SortOrder.ASCEND): List<ByteSequence> =
    getChildren(keyName, target, order).values


// Delete children keys
fun Client.deleteChildren(keyName: String): List<String> {
    val keys = getChildrenKeys(keyName)
    for (key in keys)
        deleteKey(key)
    return keys
}

// Count children keys
fun Client.getChildCount(keyName: String): Long {
    val trailingKey = keyName.ensureSuffix("/")
    val getOption: GetOption =
        getOption {
            withPrefix(trailingKey)
            withCountOnly(true)
        }
    return getResponse(trailingKey, getOption).count
}
