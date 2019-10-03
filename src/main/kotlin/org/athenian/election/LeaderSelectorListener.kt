package org.athenian.election

interface LeaderSelectorListener {
    @Throws(Exception::class)
    fun takeLeadership(selector: LeaderSelector)
}