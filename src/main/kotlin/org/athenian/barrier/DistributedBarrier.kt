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

    private val barrierPath = barrierPath(barrierName)
    private val client = Client.builder().endpoints(url).build()
    private val kvClient = client.kvClient
    private val watchClient = lazy { client.watchClient }
    private val leaseClient = lazy { client.leaseClient }
    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private val barrierLatch = lazy { CountDownLatch(1) }
    private val waitLatch = lazy { CountDownLatch(1) }

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierName.isNotEmpty()) { "Barrier name cannot be empty" }
    }

    override fun close() {
        if (leaseClient.isInitialized())
            leaseClient.value.close()
        if (watchClient.isInitialized())
            watchClient.value.close()
        kvClient.close()
        client.close()
        if (executor.isInitialized())
            executor.value.shutdown()
    }

    fun setBarrier(): Boolean {
        // Prime lease with 2 seconds to give keepAlive a chance to get started
        val lease = leaseClient.value.grant(2).get()

        // Create unique token to avoid collision from clients with same id
        val uniqueToken = "$id:${randomId(9)}"

        // Do a CAS on the key name. If it is not found, then set it
        kvClient.transaction {
            If(equals(barrierPath, CmpTarget.version(0)))
            Then(putOp(barrierPath, uniqueToken, lease.asPutOption))
        }

        // Check to see if unique value was successfully set in the CAS step
        return if (kvClient.getStringValue(barrierPath) == uniqueToken) {
            executor.value.submit {
                leaseClient.value.keepAlive(lease.id,
                                            Observers.observer(
                                                { next -> /*println("KeepAlive next resp: $next")*/ },
                                                { err -> /*println("KeepAlive err resp: $err")*/ })
                ).use {
                    barrierLatch.value.await()
                }
            }
            true
        } else {
            false
        }
    }

    fun removeBarrier(): Boolean =
        if (barrierLatch.value.count == 0L)
            false
        else {
            barrierLatch.value.countDown()
            true
        }

    fun waitOnBarrier() = waitOnBarrier(Long.MAX_VALUE.days)

    fun waitOnBarrier(duration: Duration): Boolean {

        if (kvClient.keyIsNotPresent(barrierPath))
            return true

        // Check if barrier is present before using watcher
        watchClient.value.watcher(barrierPath(barrierPath)) { resp ->
            resp.events
                .forEach { event ->
                    if (event.eventType == WatchEvent.EventType.DELETE) {
                        waitLatch.value.countDown()
                    }
                }

        }.use {
            // Check one more time
            return@waitOnBarrier if (kvClient.keyIsNotPresent(barrierPath)) {
                waitLatch.value.countDown()
                true
            } else {
                waitLatch.value.await(duration.toLongMilliseconds(), TimeUnit.MILLISECONDS)
            }
        }
    }

    companion object {
        private const val barrierPrefix = "/counters"

        private fun barrierPath(barrierName: String) =
            "${barrierPrefix}${if (barrierName.startsWith("/")) "" else "/"}$barrierName"

        fun reset(url: String, barrierName: String) {
            require(barrierName.isNotEmpty()) { "Barrier name cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient -> kvclient.delete(barrierPath(barrierName)) }
                }
        }
    }
}