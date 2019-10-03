package org.athenian.election

import com.sudothought.common.util.sleep
import kotlin.random.Random
import kotlin.time.seconds

fun main() {
    val url = "http://localhost:2379"
    val electionName = "/election/leaderElectionDemo"

    LeaderSelector.reset(url, electionName)

    val leadershipAction = { selector: LeaderSelector ->
        println("${selector.clientId} elected leader")
        val pause = Random.nextInt(5).seconds
        sleep(pause)
        println("${selector.clientId} surrendering after $pause")
    }

    LeaderSelector(url, electionName, leadershipAction)
        .use { election ->
            repeat(5) {
                election.start()
                election.waitOnLeadershipComplete()
            }
        }

    repeat(5) {
        LeaderSelector(url, electionName, leadershipAction)
            .use { election ->
                election.start()
                election.waitOnLeadershipComplete()
            }
    }
}
