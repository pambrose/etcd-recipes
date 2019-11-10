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

package io.etcd.recipes.node

import com.sudothought.common.util.randomId
import com.sudothought.common.util.sleep
import io.etcd.recipes.common.asString
import io.etcd.recipes.common.etcdExec
import io.etcd.recipes.common.getChildrenCount
import io.etcd.recipes.common.getValue
import io.etcd.recipes.common.urls
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.Test
import kotlin.time.seconds

class TransientNodeTest {

    @Test
    fun singleNodeTest() {
        val path = "/node/singleNodeTest"
        val id = randomId(8)

        etcdExec(urls) { _, kvClient -> kvClient.getValue(path, "nothing") shouldEqual "nothing" }

        TransientNode(urls, path, id).use {
            repeat(10) {
                etcdExec(urls) { _, kvClient -> kvClient.getValue(path)?.asString shouldEqual id }
                sleep(1.seconds)
            }
        }

        // Wait for node to go away
        sleep(5.seconds)
        etcdExec(urls) { _, kvClient -> kvClient.getValue(path, "nothing") shouldEqual "nothing" }
    }

    @Test
    fun multiNodeTest() {
        val count = 25
        val paths = List(count) { "/node/multiNodeTest$it" }
        val ids = List(count) { randomId(8) }

        etcdExec(urls) { _, kvClient ->
            for (path in paths)
                kvClient.getValue(path, "nothing") shouldEqual "nothing"

            kvClient.getChildrenCount("/node") shouldEqual 0
        }

        val nodes = List(count) { TransientNode(urls, paths[it], ids[it]) }

        etcdExec(urls) { _, kvClient ->
            repeat(10) {
                repeat(count) { j ->
                    kvClient.getValue(paths[j])?.asString shouldEqual ids[j]
                }
            }

            kvClient.getChildrenCount("/node") shouldEqual 25
        }

        for (node in nodes)
            node.close()

        // Wait for nodes to go away
        sleep(5.seconds)
        etcdExec(urls) { _, kvClient ->
            for (path in paths)
                kvClient.getValue(path, "nothing") shouldEqual "nothing"

            kvClient.getChildrenCount("/node") shouldEqual 0
        }
    }
}