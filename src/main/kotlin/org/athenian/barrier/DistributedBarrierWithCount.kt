package org.athenian.barrier

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import org.athenian.utils.append
import org.athenian.utils.asByteSequence
import org.athenian.utils.asPutOption
import org.athenian.utils.asString
import org.athenian.utils.countChildren
import org.athenian.utils.delete
import org.athenian.utils.deleteOp
import org.athenian.utils.ensureTrailing
import org.athenian.utils.equals
import org.athenian.utils.getChildrenKeys
import org.athenian.utils.getStringValue
import org.athenian.utils.isDone
import org.athenian.utils.keyIsPresent
import org.athenian.utils.putOp
import org.athenian.utils.randomId
import org.athenian.utils.timeUnitToDuration
import org.athenian.utils.transaction
import org.athenian.utils.watcher
import org.athenian.utils.withKvClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days

/*
    First node creates subnode /ready
    Each node creates its own subnode with keepalive on it
    Each node creates a watch for DELETE on /ready and PUT on any waiter
    Query the number of children after each PUT on waiter and DELETE /ready if memberCount seen
    Leave if DELETE of /ready is seen
*/
@ExperimentalTime
class DistributedBarrierWithCount(val url: String,
                                  val barrierPath: String,
                                  val memberCount: Int,
                                  val clientId: String) : Closeable {

    constructor(url: String,
                barrierPath: String,
                memberCount: Int) : this(url, barrierPath, memberCount, "Client:${randomId(9)}")

    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }
    private val leaseClient = lazy { client.value.leaseClient }
    private val watchClient = lazy { client.value.watchClient }
    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private val readyPath = barrierPath.append("ready")
    private val waitingPrefix = barrierPath.append("waiting")

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
    }

    private val isReadySet: Boolean get() = kvClient.keyIsPresent(readyPath)

    val waiterCount: Long get() = kvClient.countChildren(waitingPrefix)

    @Throws(InterruptedException::class)
    fun waitOnBarrier(): Boolean = waitOnBarrier(Long.MAX_VALUE.days)

    @Throws(InterruptedException::class)
    fun waitOnBarrier(timeout: Long, timeUnit: TimeUnit): Boolean =
        waitOnBarrier(timeUnitToDuration(timeout, timeUnit))

    @Throws(InterruptedException::class)
    fun waitOnBarrier(timeout: Duration): Boolean {

        val uniqueToken = "$clientId:${randomId(9)}"

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(equals(readyPath, CmpTarget.version(0)))
            Then(putOp(readyPath, uniqueToken))
        }

        val waitLatch = CountDownLatch(1)

        val waitingPath = waitingPrefix.append(uniqueToken)
        val lease = leaseClient.value.grant(2).get()
        kvClient.transaction {
            If(equals(waitingPath, CmpTarget.version(0)))
            Then(putOp(waitingPath, uniqueToken, lease.asPutOption))
        }

        // Keep key alive
        if (kvClient.getStringValue(waitingPath) == uniqueToken) {
            executor.value.submit {
                leaseClient.value.keepAlive(lease.id,
                                            Observers.observer(
                                                { /*println("KeepAlive next resp: $next")*/ },
                                                { /*println("KeepAlive err resp: $err")*/ })
                ).use {
                    waitLatch.await()
                }
            }
        }

        fun checkWaiterCount() {
            // First see if /ready is missing
            if (!isReadySet) {
                waitLatch.countDown()
            } else {
                if (waiterCount >= memberCount) {

                    waitLatch.countDown()

                    // Delete /ready key
                    kvClient.transaction {
                        If(equals(readyPath, CmpTarget.version(0)))
                        Then()
                        Else(deleteOp(readyPath))
                    }
                }
            }
        }

        checkWaiterCount()

        // Do not bother starting watcher if latch is already done
        if (waitLatch.isDone)
            return true

        // Watch for DELETE of /ready and PUTS on /waiters/*
        val adjustedKey = barrierPath.ensureTrailing("/")
        val watchOption = WatchOption.newBuilder().withPrefix(adjustedKey.asByteSequence).build()
        watchClient.watcher(adjustedKey, watchOption) { watchResponse ->
            watchResponse.events
                .forEach { watchEvent ->
                    val key = watchEvent.keyValue.key.asString
                    when {
                        key.startsWith(waitingPrefix) && watchEvent.eventType == PUT -> checkWaiterCount()
                        key.startsWith(readyPath) && watchEvent.eventType == DELETE -> waitLatch.countDown()
                    }
                }

        }.use {
            // Check one more time in case watch missed the delete just after last check
            checkWaiterCount()

            val success = waitLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
            // Cleanup if a time-out occurred
            if (!success) {
                waitLatch.countDown() // Release keep-alive waiting on latch.
                kvClient.delete(waitingPath)  // This is redundant but waiting for keep-alive to stop is slower
            }

            return@waitOnBarrier success
        }
    }

    override fun close() {
        if (watchClient.isInitialized())
            watchClient.value.close()

        if (leaseClient.isInitialized())
            leaseClient.value.close()

        if (kvClient.isInitialized())
            kvClient.value.close()

        if (client.isInitialized())
            client.value.close()

        if (executor.isInitialized())
            executor.value.shutdown()
    }

    companion object {
        fun reset(url: String, barrierPath: String) {
            require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvClient ->
                        // Delete all children
                        kvClient.getChildrenKeys(barrierPath).forEach { kvClient.delete(it) }
                    }
                }
        }
    }
}