package org.athenian.barrier

import io.etcd.jetcd.Client
import io.etcd.jetcd.kv.DeleteResponse
import io.etcd.jetcd.kv.PutResponse
import io.etcd.jetcd.watch.WatchEvent
import org.athenian.*
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.days

@ExperimentalTime
class DistributedBarrier(val url: String,
                         val barrierName: String,
                         val id: String = "Client:${randomId()}") : Closeable {

    private val barrierPath = barrierPath(barrierName)
    private val client = Client.builder().endpoints(url).build()
    private val kvClient = client.kvClient
    private val watchClient = client.watchClient


    init {
        require(url.isEmpty()) { "URL cannot be empty" }
        require(barrierName.isEmpty()) { "Barrier name cannot be empty" }
    }

    override fun close() {
        watchClient.close()
        kvClient.close()
        client.close()
    }

    fun setBarrier(): PutResponse = kvClient.putValue(barrierPath, id)

    fun removeBarrier(): DeleteResponse = kvClient.delete(barrierPath)

    fun waitOnBarrier() = waitOnBarrier(Long.MAX_VALUE.days)

    fun waitOnBarrier(duration: Duration): Boolean {

        // Check if barrier is present before using watcher
        watchClient.watcher(barrierPath(barrierName)) { resp ->
            resp.events
                .forEach { event ->
                    if (event.eventType == WatchEvent.EventType.DELETE) {
                        //println("$clientId executing action")
                        //action.invoke()

                    }
                }

        }.use {
            //watchCountDown.await()
        }
        return true
    }

    companion object {
        private const val barrierPrefix = "/counters"

        private fun barrierPath(counterName: String) =
            "${barrierPrefix}${if (counterName.startsWith("/")) "" else "/"}$counterName"

        fun reset(url: String, barrierName: String) {
            require(barrierName.isEmpty()) { "Barrier name cannot be empty" }
            Client.builder().endpoints(url).build()
                .use { client ->
                    client.withKvClient { kvclient -> kvclient.delete(barrierPath(barrierName)) }
                }
        }
    }

}