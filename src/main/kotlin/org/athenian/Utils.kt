package org.athenian

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.KV
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.options.PutOption
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

val String.asByteSequence: ByteSequence
    get() = ByteSequence.from(this.toByteArray())

val ByteSequence.asString: String
    get() = toString(Charsets.UTF_8)

val LeaseGrantResponse.asPutOption: PutOption
    get() = PutOption.newBuilder().withLeaseId(this.id).build()

@ExperimentalTime
fun repeatWithSleep(iterations: Int,
                    duration: Duration = 1.seconds,
                    block: (count: Int, startMillis: Long) -> Unit) {
    val startMillis = System.currentTimeMillis()
    repeat(iterations) { i ->
        block(i, startMillis)
        sleep(duration)
    }
}

@ExperimentalTime
fun sleep(duration: Duration) {
    Thread.sleep(duration.toLongMilliseconds())
}

infix fun KV.put(kv: Pair<String, String>): PutResponse = put(kv.first, kv.second)

fun KV.put(keyname: String, keyval: String): PutResponse = put(keyname.asByteSequence, keyval.asByteSequence).get()

fun KV.put(keyname: String, keyval: String, option: PutOption): PutResponse =
    put(keyval.asByteSequence, keyval.asByteSequence, option).get()

infix fun KV.delete(keyname: String): DeleteResponse = delete(keyname.asByteSequence).get()

infix fun KV.get(keyname: String): GetResponse = get(keyname.asByteSequence).get()