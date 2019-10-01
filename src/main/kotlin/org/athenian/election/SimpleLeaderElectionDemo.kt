package org.athenian.election

import org.athenian.sleep
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val electionName = "/election/singleClient"

    LeaderElection(url, electionName)
        .also { election ->
            val actions =
                ElectionActions(
                    onInitComplete = { println("${election.id} initialized") },
                    onElected = {
                        println("${election.id} elected leader")
                        val pause = Random.nextInt(5).seconds
                        sleep(pause)
                        println("${election.id} surrendering after $pause")
                    },
                    onFailedElection = {
                        //println("$id failed to get elected")
                    },
                    onTermComplete = {
                        println("${election.id} completed")
                        sleep(2.seconds)
                    }
                )
            election.start(actions)
            election.await()
        }
}