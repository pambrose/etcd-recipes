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

package org.athenian.util

import com.sudothought.common.util.sleep
import io.etcd.jetcd.Client
import org.athenian.jetcd.countChildren
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getChildrenStringValues
import org.athenian.jetcd.withKvClient
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val keyname = "/"

    // DistributedBarrierWithCount.reset(url, keyname)

    Client.builder().endpoints(url).build()
        .use { client ->
            client.withKvClient { kvClient ->
                kvClient.apply {
                    repeat(600) {
                        println(getChildrenKeys(keyname))
                        println(getChildrenStringValues(keyname))
                        println(countChildren(keyname))
                        sleep(1.seconds)
                    }
                }
            }
        }
}