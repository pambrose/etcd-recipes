package org.athenian;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;

import java.nio.charset.Charset;

public class TxnTest {

    public static void main(String[] args) {

        Client client = Client.builder().endpoints("http://localhost:2379").build();

        KV kv = client.getKVClient();

        ByteSequence bskey = ByteSequence.from("/version", Charset.defaultCharset());
        kv.txn().If(new Cmp(bskey, Cmp.Op.EQUAL, CmpTarget.value(bskey)))
                .Then()
                .Else();

    }
}
