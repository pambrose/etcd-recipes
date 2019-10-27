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

package io.etcd.recipes.common

import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Watch
import java.util.*

open class EtcdConnector(urls: List<String>) {

    protected val client: Lazy<Client> = lazy { connectToEtcd(urls) }
    protected val kvClient: Lazy<KV> = lazy { client.value.kvClient }
    protected val leaseClient: Lazy<Lease> = lazy { client.value.leaseClient }
    protected val watchClient: Lazy<Watch> = lazy { client.value.watchClient }
    private var closeCalled: Boolean by atomicBoolean(false)
    protected val exceptionList: Lazy<MutableList<Throwable>> =
        lazy { Collections.synchronizedList(mutableListOf<Throwable>()) }

    protected fun checkCloseNotCalled() {
        if (closeCalled) throw EtcdRecipeRuntimeException("close() already called")
    }

    val exceptions get() = if (exceptionList.isInitialized()) exceptionList.value else emptyList<Throwable>()

    val hasExceptions get() = exceptionList.isInitialized() && exceptionList.value.size > 0

    fun clearExceptions() {
        if (exceptionList.isInitialized()) exceptionList.value.clear()
    }

    @Synchronized
    open fun close() {
        if (!closeCalled) {
            if (watchClient.isInitialized())
                watchClient.value.close()

            if (leaseClient.isInitialized())
                leaseClient.value.close()

            if (kvClient.isInitialized())
                kvClient.value.close()

            if (client.isInitialized())
                client.value.close()

            closeCalled = true
        }
    }
}