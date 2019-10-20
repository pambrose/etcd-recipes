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
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;

import java.util.List;

import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.delete;
import static io.etcd.recipes.common.KVUtils.getValue;
import static io.etcd.recipes.common.KVUtils.isKeyPresent;
import static io.etcd.recipes.common.KVUtils.putValue;
import static io.etcd.recipes.common.TxnUtils.getDoesExist;
import static io.etcd.recipes.common.TxnUtils.setTo;
import static io.etcd.recipes.common.TxnUtils.transaction;
import static java.lang.String.format;

public class SetValueWithTxn {
    private static final List<String> urls = Lists.newArrayList("http://localhost:2379");
    private static final String path = "/txnexample";
    private static final String keyval = "foobar";

    public static void main(String[] args) {
        try (Client client = connectToEtcd(urls);
             KV kvClient = client.getKVClient()) {

            System.out.println("Deleting keys");
            delete(kvClient, path, keyval);

            System.out.println(format("Key present: %s", isKeyPresent(kvClient, keyval)));
            checkForKey(kvClient);
            System.out.println(format("Key present: %s", isKeyPresent(kvClient, keyval)));
            putValue(kvClient, path, "Something");
            checkForKey(kvClient);
        }
    }

    private static void checkForKey(KV kvClient) {
        transaction(kvClient, (txn) -> {
            txn.If(getDoesExist(path));
            txn.Then(setTo(keyval, format("Key %s found", path)));
            txn.Else(setTo(keyval, format("Key %s not found", path)));
            return txn;
        });

        System.out.println(format("Debug value: %s", getValue(kvClient, keyval, "not_used")));
    }
}