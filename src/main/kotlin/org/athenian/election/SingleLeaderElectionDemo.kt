package org.athenian.election

import org.athenian.sleep
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val electionName = "/election/leaderElectionDemo"

    val actions =
        ElectionActions(
            onInitComplete = { election -> println("${election.id} initialized") },
            onElected = { election ->
                println("${election.id} elected leader")
                val pause = Random.nextInt(5).seconds
                sleep(pause)
                println("${election.id} surrendering after $pause")
            },
            onFailedElection = { election ->
                //println("$id failed to get elected")
            },
            onTermComplete = { election ->
                println("${election.id} completed")
                sleep(2.seconds)
            }
        )
    LeaderElection(url, electionName, actions)
        .also { election ->
            repeat(10) {
                election.start()
                election.await()
            }
        }
}