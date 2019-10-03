package org.athenian.barrier

import com.sudothought.common.time.Conversions.Static.timeUnitToDuration
import com.sudothought.common.util.randomId
import io.etcd.jetcd.Client
import org.athenian.jetcd.append
import org.athenian.jetcd.delete
import org.athenian.jetcd.getChildrenKeys
import org.athenian.jetcd.withKvClient
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.days

class DistributedDoubleBarrier(val url: String,
                               barrierPath: String,
                               memberCount: Int,
                               val clientId: String) : Closeable {

    constructor(url: String,
                barrierPath: String,
                memberCount: Int) : this(url, barrierPath, memberCount, "Client:${randomId(9)}")

    private val enterBarrier = DistributedBarrierWithCount(url, barrierPath.append("enter"), memberCount)
    private val leaveBarrier = DistributedBarrierWithCount(url, barrierPath.append("leave"), memberCount)

    init {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(barrierPath.isNotEmpty()) { "Barrier path cannot be empty" }
        require(memberCount > 0) { "Member count must be > 0" }
    }

    val enterWaiterCount: Long get() = enterBarrier.waiterCount

    val leaveWaiterCount: Long get() = leaveBarrier.waiterCount

    fun enter(): Boolean = enter(Long.MAX_VALUE.days)

    fun enter(timeout: Long, timeUnit: TimeUnit): Boolean = enter(timeUnitToDuration(timeout, timeUnit))

    fun enter(timeout: Duration): Boolean = enterBarrier.waitOnBarrier(timeout)

    fun leave(): Boolean = leave(Long.MAX_VALUE.days)

    fun leave(timeout: Long, timeUnit: TimeUnit): Boolean = leave(timeUnitToDuration(timeout, timeUnit))

    fun leave(timeout: Duration): Boolean = leaveBarrier.waitOnBarrier(timeout)

    override fun close() {
        enterBarrier.close()
        leaveBarrier.close()
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