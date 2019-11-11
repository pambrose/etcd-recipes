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

import com.sudothought.common.concurrent.BooleanMonitor
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import io.etcd.jetcd.Client
import java.io.Closeable
import java.util.Collections.synchronizedList

open class EtcdConnector(val client: Client) : Closeable {

    protected var startCalled by atomicBoolean(false)
    protected val startThreadComplete = BooleanMonitor(false)
    protected var closeCalled: Boolean by atomicBoolean(false)
    protected val exceptionList: Lazy<MutableList<Throwable>> = lazy { synchronizedList(mutableListOf<Throwable>()) }

    protected fun checkCloseNotCalled() {
        if (closeCalled) throw EtcdRecipeRuntimeException("close() already called")
    }

    val exceptions get() = if (exceptionList.isInitialized()) exceptionList.value else emptyList<Throwable>()

    val hasExceptions get() = exceptionList.isInitialized() && exceptionList.value.size > 0

    fun clearExceptions() {
        if (exceptionList.isInitialized()) exceptionList.value.clear()
    }

    protected fun checkStartCalled() {
        if (!startCalled) throw EtcdRecipeRuntimeException("start() not called")
    }

    @Synchronized
    override fun close() {
            closeCalled = true
    }

    companion object {
        internal const val tokenLength = 7
        internal const val defaultTtlSecs = 2L
    }
}