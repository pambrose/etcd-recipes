package org.athenian

import io.etcd.jetcd.*
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lock.LockResponse
import io.etcd.jetcd.lock.UnlockResponse
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.PutOption
import kotlin.random.Random
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

fun KV.delete(vararg keynames: String) = keynames.forEach { delete(it) }

infix fun KV.delete(keyname: String): DeleteResponse = delete(keyname.asByteSequence).get()

infix fun KV.get(keyname: String): GetResponse = get(keyname.asByteSequence).get()

infix fun KV.getValue(keyname: String): String? = get(keyname).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value?.asString

fun KV.getValue(keyname: String, defaultStr: String = ""): String = getValue(keyname) ?: defaultStr

fun Lock.lock(keyname: String, leaseId: Long): LockResponse = lock(keyname.asByteSequence, leaseId).get()

fun Lock.unlock(keyname: String): UnlockResponse = unlock(keyname.asByteSequence).get()

fun put(keyname: String, keyval: String, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    Op.put(keyname.asByteSequence, keyval.asByteSequence, option)

fun <T> equals(keyname: String, target: CmpTarget<T>): Cmp = Cmp(keyname.asByteSequence, Cmp.Op.EQUAL, target)

fun <T> less(keyname: String, target: CmpTarget<T>): Cmp = Cmp(keyname.asByteSequence, Cmp.Op.LESS, target)

fun <T> greater(keyname: String, target: CmpTarget<T>): Cmp = Cmp(keyname.asByteSequence, Cmp.Op.GREATER, target)

fun Client.withWatchClient(block: (watchClient: Watch) -> Unit) = watchClient.use { block(it) }

fun Client.withLeaseClient(block: (leaseClient: Lease) -> Unit) = leaseClient.use { block(it) }

fun Client.withLockClient(block: (lockClient: Lock) -> Unit) = lockClient.use { block(it) }

fun Client.withMaintClient(block: (maintClient: Maintenance) -> Unit) = maintenanceClient.use { block(it) }

fun Client.withClusterClient(block: (clusterClient: Cluster) -> Unit) = clusterClient.use { block(it) }

fun Client.withAuthrClient(block: (authClient: Auth) -> Unit) = authClient.use { block(it) }

fun Client.withKvClient(block: (kvClient: KV) -> Unit) = kvClient.use { block(it) }

fun KV.transaction(block: Txn.() -> Txn) {
    txn()
        .run {
            block()
            commit().get()
        }
}

fun randomId(length: Int = 10): String {
    val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..length)
        .map { Random.nextInt(0, charPool.size) }
        .map { i -> charPool.get(i) }
        .joinToString("");
}