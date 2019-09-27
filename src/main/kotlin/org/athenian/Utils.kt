package org.athenian

import com.google.common.primitives.Ints
import com.google.common.primitives.Longs
import io.etcd.jetcd.*
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lock.LockResponse
import io.etcd.jetcd.lock.UnlockResponse
import io.etcd.jetcd.op.Cmp
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.op.Op
import io.etcd.jetcd.options.PutOption
import java.util.concurrent.Semaphore
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

val String.asByteSequence: ByteSequence
    get() = ByteSequence.from(this.toByteArray())

val Long.asByteSequence: ByteSequence
    get() = ByteSequence.from(Longs.toByteArray(this))


val ByteSequence.asString: String
    get() = toString(Charsets.UTF_8)

val ByteSequence.asInt: Int
    get() = Ints.fromByteArray(bytes)

val ByteSequence.asLong: Long
    get() = Longs.fromByteArray(bytes)

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

fun KV.delete(keyname: String): DeleteResponse = delete(keyname.asByteSequence).get()

fun KV.get(keyname: String): GetResponse = get(keyname.asByteSequence).get()

fun KV.getStringValue(keyname: String): String? =
    get(keyname).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value?.asString

fun KV.getStringValue(keyname: String, defaultVal: String): String = getStringValue(keyname) ?: defaultVal

fun KV.getIntValue(keyname: String): Int? =
    get(keyname).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value?.asInt

fun KV.getIntValue(keyname: String, defaultVal: Int): Int = getIntValue(keyname) ?: defaultVal


fun KV.getLongValue(keyname: String): Long? =
    get(keyname).kvs.takeIf { it.isNotEmpty() }?.get(0)?.value?.asLong

fun KV.getLongValue(keyname: String, defaultVal: Long): Long = getLongValue(keyname) ?: defaultVal

fun Lock.lock(keyname: String, leaseId: Long): LockResponse = lock(keyname.asByteSequence, leaseId).get()

fun Lock.unlock(keyname: String): UnlockResponse = unlock(keyname.asByteSequence).get()

fun put(keyname: String, keyval: String, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    put(keyname, keyval.asByteSequence, option)

fun put(keyname: String, keyval: Long, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    put(keyname, keyval.asByteSequence, option)

fun put(keyname: String, keyval: ByteSequence, option: PutOption = PutOption.DEFAULT): Op.PutOp =
    Op.put(keyname.asByteSequence, keyval, option)

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

fun KV.transaction(block: Txn.() -> Txn): TxnResponse {
    return txn()
        .run {
            block()
            commit()
        }.get()
}

fun randomId(length: Int = 10): String {
    val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..length)
        .map { Random.nextInt(0, charPool.size) }
        .map { i -> charPool[i] }
        .joinToString("")
}

fun <T> Semaphore.withLock(block: () -> T): T {
    acquire()
    try {
        return block()
    } finally {
        release()
    }
}