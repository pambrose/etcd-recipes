package org.athenian.election

import org.athenian.election.LeaderSelector.Static.getParticipants
import org.athenian.utils.sleep
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val electionName = "/threadedClient"
    val count = 5
    val latch = CountDownLatch(count)

    LeaderSelector.reset(url, electionName)

    val leadershipAction =
        { selector: LeaderSelector ->
            println("${selector.id} elected leader")
            val pause = Random.nextInt(5).seconds
            sleep(pause)
            println("${selector.id} surrendering after $pause")
        }

    repeat(count) {
        thread {
            LeaderSelector(url, electionName, leadershipAction, "Thread$it")
                .use { election ->
                    election.start()
                    election.await()
                }
            latch.countDown()
        }
    }

    while (latch.count > 0) {
        println("Participants: ${getParticipants(url, electionName)}")
        sleep(1.seconds)
    }

    latch.await()
}