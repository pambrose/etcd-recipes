package org.athenian.election

import com.sudothought.common.util.sleep
import org.athenian.election.LeaderSelector.Static.getParticipants
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val electionName = "/threadedClient"
    val count = 5
    val latch = CountDownLatch(count)

    LeaderSelector.reset(url, electionName)

    val leadershipAction =
        { selector: LeaderSelector ->
            println("${selector.clientId} elected leader")
            val pause = Random.nextInt(5).seconds
            sleep(pause)
            println("${selector.clientId} surrendering after $pause")
        }

    repeat(count) {
        thread {
            LeaderSelector(url, electionName, leadershipAction, "Thread$it")
                .use { election ->
                    election.start()
                    election.waitOnLeadershipComplete()
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