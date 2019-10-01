package org.athenian.barrier

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import io.etcd.jetcd.Watch
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
import org.athenian.append
import org.athenian.asByteSequence
import org.athenian.asPutOption
import org.athenian.asString
import org.athenian.countChildren
import org.athenian.delete
import org.athenian.deleteOp
import org.athenian.ensureTrailing
import org.athenian.equals
import org.athenian.getChildrenKeys
import org.athenian.getStringValue
import org.athenian.isDone
import org.athenian.keyIsPresent
import org.athenian.putOp
import org.athenian.randomId
import org.athenian.timeUnitToDuration
import org.athenian.transaction
import org.athenian.watcher
import org.athenian.withKvClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days

@ExperimentalTime
class DistributedDoubleBarrierNoLeaveTimeout(val url: String,
                                             val barrierPath: String,
                                             val memberCount: Int,
                                             val id: String = "Client:${randomId(6)}") : Closeable {

    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }
    private val leaseClient = lazy { client.value.leaseClient }
    private val watchClient = lazy { client.value.watchClient }
    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private val enterCalled = AtomicBoolean(false)
    private val leaveCalled = AtomicBoolean(false)
    private val watcher = AtomicReference<Watch.Watcher>()
    private val waitingPath = AtomicReference<String>()
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

        val uniqueToken = "$id:${randomId(9)}"

        enterCalled.set(true)

        // Do a CAS on the /ready name. If it is not found, then set it
        kvClient.transaction {
            If(equals(readyPath, CmpTarget.version(0)))
            Then(putOp(readyPath, uniqueToken))
        }

        waitingPath.set("$waitingPrefix/$uniqueToken")
        val lease = leaseClient.value.grant(2).get()
        kvClient.transaction {
            If(equals(waitingPath.get(), CmpTarget.version(0)))
            Then(putOp(waitingPath.get(), uniqueToken, lease.asPutOption))
        }

        // Keep key alive
        if (kvClient.getStringValue(waitingPath.get()) == uniqueToken) {
            executor.value.submit {
                leaseClient.value.keepAlive(lease.id,
                                            Observers.observer(
                                                { /*println("KeepAlive next resp: $next")*/ },
                                                { /*println("KeepAlive err resp: $err")*/ })
                ).use {
                    keepAliveLatch.await()
                }
            }
        }

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
        if (enterWaitLatch.isDone)
            return true

        // Watch for DELETE of /ready and PUTS on /waiters/*
        val adjustedKey = barrierPath.ensureTrailing("/")
        val watchOption = WatchOption.newBuilder().withPrefix(adjustedKey.asByteSequence).build()
        watcher.set(
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

            })

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

    fun leave(timeout: Long, timeUnit: TimeUnit): Boolean = leave(timeUnitToDuration(timeout, timeUnit))

    fun leave(timeout: Duration): Boolean {

        check(enterCalled.get()) { "enter() must be called before leave()" }

        leaveCalled.set(true)

        // println("Deleting ${waitingPath.get()}")
        kvClient.delete(waitingPath.get())

        checkWaiterCountInLeave()

        return leaveLatch.await(timeout.toLongMilliseconds(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (watcher.get() != null)
            watcher.get().close()

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