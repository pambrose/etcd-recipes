package org.athenian.barrier

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import org.athenian.asPutOption
import org.athenian.delete
import org.athenian.equals
import org.athenian.getStringValue
import org.athenian.keyIsPresent
import org.athenian.putOp
import org.athenian.randomId
import org.athenian.transaction
import org.athenian.watcher
import org.athenian.withKvClient
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days

@ExperimentalTime
class DistributedBarrier(val url: String,
                         val barrierPath: String,
                         val waitOnMissingBarriers: Boolean = true,
                         val id: String = "Client:${randomId()}") : Closeable {

    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }
    private val leaseClient = lazy { client.value.leaseClient }
    private val watchClient = lazy { client.value.watchClient }
    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private val barrierLatch = CountDownLatch(1)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
    }

    val isBarrierSet: Boolean get() = kvClient.keyIsPresent(barrierPath)

    fun setBarrier(): Boolean {

        if (kvClient.keyIsPresent(barrierPath))
            return false

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$id:${randomId(9)}"

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.value.grant(2).get()

        // Do a CAS on the key name. If it is not found, then set it
        val txn =
            kvClient.transaction {
                If(equals(barrierPath, CmpTarget.version(0)))
                Then(putOp(barrierPath, uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (txn.isSucceeded && kvClient.getStringValue(barrierPath) == uniqueToken) {
            executor.value.submit {
                leaseClient.value.keepAlive(lease.id,
                                            Observers.observer(
                                                { next -> /*println("KeepAlive next resp: $next")*/ },
                                                { err -> /*println("KeepAlive err resp: $err")*/ })
                ).use {
                    barrierLatch.await()
                }
            }
            true
        } else {
            false
        }
    }

    fun removeBarrier(): Boolean =
        if (barrierLatch.count == 0L) {
            false
        } else {
            barrierLatch.countDown()
            true
        }

    fun waitOnBarrier() = waitOnBarrier(Long.MAX_VALUE.days)

    fun waitOnBarrier(duration: Duration): Boolean {
        // Check if barrier is present before using watcher
        if (!waitOnMissingBarriers && !isBarrierSet)
            return true

        val waitLatch = CountDownLatch(1)

        watchClient.watcher(barrierPath) { watchResponse ->
            watchResponse.events
                .forEach { watchEvent ->
                    if (watchEvent.eventType == DELETE)
                        waitLatch.countDown()
                }

        }.use {
            // Check one more time in case watch missed the delete just after last check
            if (!waitOnMissingBarriers && !isBarrierSet)
                waitLatch.countDown()

            return@waitOnBarrier waitLatch.await(duration.toLongMilliseconds(), TimeUnit.MILLISECONDS)
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
                    client.withKvClient { it.delete(barrierPath) }
                }
        }
    }
}