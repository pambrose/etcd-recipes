package org.athenian.election

import org.athenian.utils.sleep
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
fun main() {
    val url = "http://localhost:2379"
    val electionName = "/threadedClient"

    LeaderElection.reset(url, electionName)

    val actions =
        ElectionActions(
            onElected = {
                println("${it.id} elected leader")
                val pause = Random.nextInt(5).seconds
                sleep(pause)
                println("${it.id} surrendering after $pause")
            }
        )

    val participants = List(3) { LeaderElection(url, electionName, actions, "Thread$it") }

    participants
        .onEach { it.start() }
        .forEach { it.await() }
}