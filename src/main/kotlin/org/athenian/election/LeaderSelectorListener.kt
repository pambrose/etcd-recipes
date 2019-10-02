package org.athenian.election

import kotlin.time.ExperimentalTime

@ExperimentalTime
interface LeaderSelectorListener {
    @Throws(Exception::class)
    fun takeLeadership(selector: LeaderSelector)
}