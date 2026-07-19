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

package io.etcd.recipes.cache

/**
 * A [TypedPathChildrenCache] change: the decoded counterpart to [PathChildrenCacheEvent]. [data]
 * is the child's decoded value (null on `CHILD_REMOVED` when the prior value is absent), and
 * [initialData] carries the decoded snapshot on an `INITIALIZED` event. Reuses
 * [PathChildrenCacheEvent.Type].
 */
class TypedPathChildrenCacheEvent<T>(
  val childName: String,
  val type: PathChildrenCacheEvent.Type,
  val data: T?,
) {
  internal var initialDataVal: List<TypedChildData<T>> = emptyList()

  val initialData: List<TypedChildData<T>> get() = initialDataVal

  override fun toString() = "TypedPathChildrenCacheEvent{type=$type, childName=$childName, data=$data}"
}
