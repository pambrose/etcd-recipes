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
import io.etcd.recipes.common.KVUtils;
import io.etcd.recipes.common.TxnUtils;

import java.util.List;

import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.*;
import static io.etcd.recipes.common.TxnUtils.getDoesExist;
import static java.lang.String.format;

public class SetValueWithTxn {
    private static List<String> urls = Lists.newArrayList("http://localhost:2379");
    private static String path = "/txnexample";
    private static String keyval = "foobar";

    public static void main(String[] args) {
        try (Client client = connectToEtcd(urls);
             KV kvClient = client.getKVClient()) {

            System.out.println("Deleting keys");
            delete(kvClient, path, keyval);

            System.out.println(format("Key present: %s", isKeyPresent(kvClient, keyval)));
            checkForKey(kvClient);
            System.out.println(format("Key present: %s", isKeyPresent(kvClient, keyval)));
            KVUtils.putValue(kvClient, path, "Something");
            checkForKey(kvClient);
        }
    }

    private static void checkForKey(KV kvClient) {
        TxnUtils.transaction(kvClient, (txn) -> {
            txn.If(getDoesExist(path));
            txn.Then(TxnUtils.setTo(keyval, format("Key %s found", path)));
            txn.Else(TxnUtils.setTo(keyval, format("Key %s not found", path)));
            return txn;
        });

        System.out.println(format("Debug value: %s", getValue(kvClient, keyval, "not_used")));
    }
}