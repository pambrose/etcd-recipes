package org.athenian.barrier

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.watch.WatchEvent
import org.athenian.*
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days

@ExperimentalTime
class DistributedBarrier(val url: String,
                         barrierName: String,
                         val id: String = "Client:${randomId()}") : Closeable {

    private val barrierPath = keyName(barrierName)
    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }
    private val leaseClient = lazy { client.value.leaseClient }
    private val watchClient = lazy { client.value.watchClient }
    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private val barrierLatch = CountDownLatch(1)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierName.isNotEmpty()) { "Barrier name cannot be empty" }
    }

    override fun close() {
        if (kvClient.isInitialized())
            kvClient.value.close()
        if (client.isInitialized())
            client.value.close()
        if (leaseClient.isInitialized())
            leaseClient.value.close()
        if (watchClient.isInitialized())
            watchClient.value.close()
        if (executor.isInitialized())
            executor.value.shutdown()
    }

    val isBarrierSet: Boolean get() = kvClient.value.keyIsPresent(barrierPath)

    fun setBarrier(): Boolean {

        if (kvClient.value.keyIsPresent(barrierPath))
            return false

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$id:${randomId(9)}"

        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.value.grant(2).get()

        // Do a CAS on the key name. If it is not found, then set it
        val txn =
            kvClient.value.transaction {
                If(equals(barrierPath, CmpTarget.version(0)))
                Then(putOp(barrierPath, uniqueToken, lease.asPutOption))
            }

        // Check to see if unique value was successfully set in the CAS step
        return if (txn.isSucceeded && kvClient.value.getStringValue(barrierPath) == uniqueToken) {
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
        // Do a quick initial check
        if (kvClient.value.keyIsNotPresent(barrierPath))
            return true

        val waitLatch = CountDownLatch(1)

        // Check if barrier is present before using watcher
        watchClient.value.watcher(barrierPath) { watchResponse ->
            watchResponse.events
                .forEach { event ->
                    if (event.eventType == WatchEvent.EventType.DELETE)
                        waitLatch.countDown()
                }

        }.use {
            // Check one more time in case watch missed the delete just after last check
            if (kvClient.value.keyIsNotPresent(barrierPath))
                waitLatch.countDown()

            return@waitOnBarrier waitLatch.await(duration.toLongMilliseconds(), TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private const val barrierPrefix = "/counters"

        private fun keyName(barrierName: String) =
            "${barrierPrefix}${if (barrierName.startsWith("/")) "" else "/"}$barrierName"

        fun reset(url: String, barrierName: String) {
            require(barrierName.isNotEmpty()) { "Barrier name cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { it.delete(keyName(barrierName)) }
                }
        }
    }
}