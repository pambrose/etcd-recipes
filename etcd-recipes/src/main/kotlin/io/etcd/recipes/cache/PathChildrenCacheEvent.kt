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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.etcd.recipes.cache

import io.etcd.jetcd.ByteSequence

class PathChildrenCacheEvent(val childName: String, val type: Type, val data: ByteSequence?) {

    enum class Type {
        CHILD_ADDED,
        CHILD_UPDATED,
        CHILD_REMOVED,
        INITIALIZED
    }

    internal var initialDataVal: List<ChildData> = mutableListOf()

    /**
     * Special purpose method. When an [Type.INITIALIZED]
     * event is received, you can call this method to
     * receive the initial state of the cache.
     *
     * @return initial state of cache for [Type.INITIALIZED] events. Otherwise, `null`.
     */
    val initialData: List<ChildData> get() = initialDataVal

    override fun toString() = "PathChildrenCacheEvent{type=$type, data=$data}"
}