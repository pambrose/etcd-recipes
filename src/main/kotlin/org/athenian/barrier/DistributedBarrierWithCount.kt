package org.athenian.barrier

import io.etcd.jetcd.Client
import io.etcd.jetcd.Observers
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent.EventType.DELETE
import io.etcd.jetcd.watch.WatchEvent.EventType.PUT
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
class DistributedBarrierWithCount(val url: String,
                                  val barrierPath: String,
                                  val memberCount: Int,
                                  val id: String = "Client:${randomId(6)}") : Closeable {

    private val client = lazy { Client.builder().endpoints(url).build() }
    private val kvClient = lazy { client.value.kvClient }
    private val leaseClient = lazy { client.value.leaseClient }
    private val watchClient = lazy { client.value.watchClient }
    private val executor = lazy { Executors.newSingleThreadExecutor() }
    private val readyPath = "$barrierPath/ready"
    private val waitingPrefix = "$barrierPath/waiting"

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
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

    val isReadySet: Boolean get() = kvClient.keyIsPresent(readyPath)

    /*
        First node creates subnode /ready
        Each node creates its own subnode with keepalive on it
        Everyone creates a watch for /ready
        Query the number of children, if too few, wait on DELETE of /ready
        otherwise delete /ready and trigger other nodes
    */

    val waiterCount: Long get() = kvClient.countChildren(waitingPrefix)

    fun waitOnBarrier() = waitOnBarrier(Long.MAX_VALUE.days)

    fun waitOnBarrier(duration: Duration): Boolean {

        val uniqueToken = "$id:${randomId(9)}"

        // Do a CAS on the /ready name. If it is not found, then set it
        val readyTxn =
            kvClient.transaction {
                If(equals(readyPath, CmpTarget.version(0)))
                Then(putOp(readyPath, uniqueToken))
            }

        val waitLatch = CountDownLatch(1)

        val waitingPath = "$waitingPrefix/$uniqueToken"
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
                                                { next -> /*println("KeepAlive next resp: $next")*/ },
                                                { err -> /*println("KeepAlive err resp: $err")*/ })
                ).use {
                    waitLatch.await()
                }
            }
        }

        fun checkOnWaiterCount() {
            if (!isReadySet) {
                waitLatch.countDown()
            } else {
                if (waiterCount >= memberCount) {

                    waitLatch.countDown()

                    val deleteTxn =
                        kvClient.transaction {
                            If(equals(readyPath, CmpTarget.version(0)))
                            Then()
                            Else(deleteOp(readyPath))
                        }
                }
            }
        }

        checkOnWaiterCount()

        if (waitLatch.count == 0L || !isReadySet) {
            return true
        }

        // Watch /ready value
        val k = barrierPath.ensureTrailing("/")
        watchClient.watcher(k, WatchOption.newBuilder().withPrefix(k.asByteSequence).build()) { watchResponse ->
            watchResponse.events
                .forEach { watchEvent ->
                    val key = watchEvent.keyValue.key.asString
                    when {
                        key.startsWith(waitingPrefix) && watchEvent.eventType == PUT -> checkOnWaiterCount()
                        key.startsWith(readyPath) && watchEvent.eventType == DELETE -> waitLatch.countDown()
                    }
                }

        }.use {
            // Check one more time in case watch missed the delete just after last check
            checkOnWaiterCount()

            val timeout = waitLatch.await(duration.toLongMilliseconds(), TimeUnit.MILLISECONDS)
            if (!timeout) {
                waitLatch.countDown()
                kvClient.delete(waitingPath)  // This is redundant but waiting for keep-alive to stop is slower
            }

            return@waitOnBarrier timeout
        }
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