package org.athenian.counter

import org.athenian.randomId

class DistributedAtomicLong(val url: String, val counterPath: String) {

    val id: String = "DALClient:${randomId()}"

    fun get() {

    }

    fun increment() {

    }

    fun decrement() {

    }

    fun add() {

    }

    fun subtract() {

    }

}