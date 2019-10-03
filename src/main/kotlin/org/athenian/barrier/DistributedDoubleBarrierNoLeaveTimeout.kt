package org.athenian.barrier

import com.sudothought.common.concurrent.isFinished
import com.sudothought.common.delegate.AtomicDelegates.atomicBoolean
import com.sudothought.common.delegate.AtomicDelegates.nonNullableReference
import com.sudothought.common.delegate.AtomicDelegates.nullableReference
import com.sudothought.common.time.Conversions.Static.timeUnitToDuration
import com.sudothought.common.util.randomId
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import org.athenian.jetcd.append
import org.athenian.jetcd.asByteSequence
import org.athenian.jetcd.asPutOption
import org.athenian.jetcd.asString
import org.athenian.jetcd.countChildren
import org.athenian.jetcd.delete
import org.athenian.jetcd.deleteOp
import org.athenian.jetcd.ensureTrailing
import org.athenian.jetcd.equals
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.getStringValue
import org.athenian.jetcd.keepAliveUntil
import org.athenian.jetcd.keyIsPresent
import org.athenian.jetcd.putOp
import org.athenian.jetcd.transaction
import org.athenian.jetcd.watcher
import org.athenian.jetcd.withKvClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class DistributedDoubleBarrierNoLeaveTimeout(val url: String,
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
    private var enterCalled by atomicBoolean(false)
    private var leaveCalled by atomicBoolean(false)
    private var watcher by nullableReference<Watch.Watcher?>()
    private var waitingPath by nonNullableReference<String>()
    private val enterWaitLatch = CountDownLatch(1)
    private val keepAliveLatch = CountDownLatch(1)
    private val leaveLatch = CountDownLatch(1)
    private val readyPath = barrierPath.append("ready")
    private val waitingPrefix = barrierPath.append("waiting")

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
    }

    private val isReadySet: Boolean get() = kvClient.keyIsPresent(readyPath)

    val waiterCount: Long get() = kvClient.countChildren(waitingPrefix)

    fun enter(): Boolean = enter(Long.MAX_VALUE.days)

    fun enter(timeout: Long, timeUnit: TimeUnit): Boolean = enter(timeUnitToDuration(timeout, timeUnit))

    fun enter(timeout: Duration): Boolean {

        val uniqueToken = "$clientId:${randomId(9)}"

        enterCalled = true

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(equals(readyPath, CmpTarget.version(0)))
            Then(putOp(readyPath, uniqueToken))
        }

        waitingPath = "$waitingPrefix/$uniqueToken"
        val lease = leaseClient.value.grant(2).get()

        val txn =
            kvClient.transaction {
                If(equals(waitingPath, CmpTarget.version(0)))
                Then(putOp(waitingPath, uniqueToken, lease.asPutOption))
            }

        check(txn.isSucceeded) { "Failed to set waitingPath" }
        check(kvClient.getStringValue(waitingPath) == uniqueToken) { "Failed to assign waitingPath unique value" }

        // Keep key alive
        executor.value.submit { leaseClient.value.keepAliveUntil(lease) { keepAliveLatch.await() } }

        fun checkWaiterCountInEnter() {
            // First see if /ready is missing
            if (!isReadySet) {
                enterWaitLatch.countDown()
            } else {
                if (waiterCount >= memberCount) {

                    enterWaitLatch.countDown()

                    // Delete /ready key
                    kvClient.transaction {
                        If(equals(readyPath, CmpTarget.version(0)))
                        Then()
                        Else(deleteOp(readyPath))
                    }
                }
            }
        }

        checkWaiterCountInEnter()

        // Do not bother starting watcher if latch is already done
        if (enterWaitLatch.isFinished)
            return true

        // Watch for DELETE of /ready and PUTS on /waiters/*
        val adjustedKey = barrierPath.ensureTrailing("/")
        val watchOption = WatchOption.newBuilder().withPrefix(adjustedKey.asByteSequence).build()
        watcher =
            watchClient.watcher(adjustedKey, watchOption) { watchResponse ->
                watchResponse.events
                    .forEach { watchEvent ->
                        val key = watchEvent.keyValue.key.asString
                        when {
                            // enter() events
                            key.startsWith(readyPath) && watchEvent.eventType == DELETE -> enterWaitLatch.countDown()
                            key.startsWith(waitingPrefix) && watchEvent.eventType == PUT -> checkWaiterCountInEnter()
                            // leave() events
                            key.startsWith(waitingPrefix) && watchEvent.eventType == DELETE -> checkWaiterCountInLeave()
                        }
                    }

            }

        // Check one more time in case watch missed the delete just after last check
        checkWaiterCountInEnter()

        val success = enterWaitLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
        // Cleanup if a time-out occurred
        if (!success)
            enterWaitLatch.countDown() // Release keep-alive waiting on latch.

        return success
    }

    private fun checkWaiterCountInLeave() {
        if (waiterCount == 0L) {
            keepAliveLatch.countDown()
            leaveLatch.countDown()
        }
    }

    fun leave(): Boolean = leave(Long.MAX_VALUE.days)

    fun leave(timeout: Long, timeUnit: TimeUnit): Boolean = leave(timeUnitToDuration(timeout,
                                                                                     timeUnit))

    fun leave(timeout: Duration): Boolean {

        check(enterCalled) { "enter() must be called before leave()" }

        leaveCalled = true

        // println("Deleting ${waitingPath.get()}")
        kvClient.delete(waitingPath)

        checkWaiterCountInLeave()

        return leaveLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        watcher?.close()

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