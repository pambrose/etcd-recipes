package org.athenian.leader

import io.etcd.jetcd.*
import io.etcd.jetcd.op.CmpTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import org.athenian.*
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val keyname = "/election/leader"

    // This will not return until election failure or relinquishes leadership
    fun attemptToBecomeLeader(leaseClient: Lease, kvclient: KV, clientId: String) {

        val electionAttemptId = "$clientId:${Random.nextLong()}"
        val lease = leaseClient.grant(1).get()

        kvclient.txn()
            .run {
                If(equals(keyname, CmpTarget.version(0)))
                Then(put(keyname, electionAttemptId, lease.asPutOption))
                //Else(put(debug, "Key $keyname found"))
                commit().get()
            }

        val keyval = kvclient.getValue(keyname)
        if (keyval == electionAttemptId) {
            println("$clientId elected leader")
            leaseClient.keepAlive(lease.id,
                                  Observers.observer(
                                      { next -> /*println("KeepAlive next resp: $next")*/ },
                                      { err -> /*println("KeepAlive err resp: $err")*/ })
            ).use {
                val pause = Random.nextInt(10).seconds
                sleep(pause)
                println("$clientId surrendering after $pause")
            }
            sleep(5.seconds)
        }
    }

    fun waitForNextElection(watchClient: Watch, clientId: String, action: () -> Unit): Watch.Watcher {
        val watchOptions = WatchOption.newBuilder().withRevision(0).build()
        return watchClient.watch(keyname.asByteSequence, watchOptions) { resp ->
            resp.events.forEach { event ->
                //println("Watch event: ${event.eventType} ${event.keyValue.value.asString}")
                if (event.eventType == WatchEvent.EventType.DELETE) {
                    //println("$clientId executing action")
                    action.invoke()
                }
            }
        }
    }

    Client.builder().endpoints(url).build()
        .use { client ->
            client.kvClient
                .use { kvclient ->
                    println("Resetting keys")
                    kvclient.delete(keyname)
                }
        }

    sleep(1.seconds)

    repeat(30) {
        thread {
            val clientId = "Thread$it"
            val pauseTime = Random.nextInt(500).milliseconds

            sleep(pauseTime)

            Client.builder().endpoints(url).build()
                .use { client ->
                    client.leaseClient
                        .use { leaseClient ->
                            client.watchClient
                                .use { watchClient ->
                                    client.kvClient
                                        .use { kvclient ->
                                            val countdown = CountDownLatch(1)
                                            thread {
                                                val watcher = waitForNextElection(watchClient, clientId) {
                                                    attemptToBecomeLeader(leaseClient, kvclient, clientId)
                                                }
                                            }
                                            // Give the watcher a chance to start
                                            sleep(2.seconds)

                                            attemptToBecomeLeader(leaseClient, kvclient, clientId)

                                            countdown.await()
                                        }
                                }
                        }
                }
        }
    }
}