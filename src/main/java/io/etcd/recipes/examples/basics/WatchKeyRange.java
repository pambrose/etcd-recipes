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

package io.etcd.recipes.examples.basics;

import com.google.common.collect.Lists;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Watch.Watcher;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.recipes.common.ChildrenUtils;
import io.etcd.recipes.common.KVUtils;
import kotlin.Unit;

import java.util.List;

import static com.github.pambrose.common.util.JavaFuncs.sleepSecs;
import static io.etcd.recipes.common.BuilderUtils.watchOption;
import static io.etcd.recipes.common.ByteSequenceUtils.getAsByteSequence;
import static io.etcd.recipes.common.ChildrenUtils.getChildCount;
import static io.etcd.recipes.common.ChildrenUtils.getChildren;
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.putValue;
import static io.etcd.recipes.common.KeyValueUtils.getAsString;
import static io.etcd.recipes.common.PairUtils.getAsString;
import static io.etcd.recipes.common.WatchUtils.watcher;
import static java.lang.String.format;

public class WatchKeyRange {

    public static void main(String[] args) {
        List<String> urls = Lists.newArrayList("http://localhost:2379");
        String path = "/watchkeyrange";

        ByteSequence pathBS = getAsByteSequence(path);
        WatchOption watchOption = watchOption((WatchOption.Builder builder) -> builder.withPrefix(pathBS));

        try (Client client = connectToEtcd(urls);
             Watcher watcher =
                     watcher(client,
                             path,
                             watchOption,
                             (watchResponse) -> {
                                 watchResponse.getEvents().forEach((event) ->
                                         System.out.println(format("%s for %s",
                                                 event.getEventType(),
                                                 getAsString(event.getKeyValue())
                                         )));
                                 return Unit.INSTANCE;
                             })) {

            // Create empty root
            putValue(client, path, "root");

            System.out.println("After creation:");
            System.out.println(getAsString(getChildren(client, path)));
            System.out.println(getChildCount(client, path));

            sleepSecs(5);

            // Add children
            putValue(client, path + "/election/a", "a");
            putValue(client, path + "/election/b", "bb");
            putValue(client, path + "/waiting/c", "ccc");
            putValue(client, path + "/waiting/d", "dddd");

            System.out.println("\nAfter putValues:");
            System.out.println(getAsString(getChildren(client, path)));
            System.out.println(getChildCount(client, path));

            System.out.println("\nElections only:");
            System.out.println(getAsString(getChildren(client, path + "/election")));
            System.out.println(getChildCount(client, path + "/election"));

            System.out.println("\nWaitings only:");
            System.out.println(getAsString(getChildren(client, path + "/waiting")));
            System.out.println(getChildCount(client, path + "/waiting"));

            sleepSecs(5);

            // Delete root
            KVUtils.deleteKey(client, path);

            // Delete children
            ChildrenUtils.getChildrenKeys(client, path).forEach((keyName) -> {
                System.out.println(format("Deleting key: %s", keyName));
                KVUtils.deleteKey(client, keyName);
            });

            System.out.println("\nAfter delete:");
            System.out.println(getAsString(getChildren(client, path)));
            System.out.println(getChildCount(client, path));

            sleepSecs(5);
        }
    }
}