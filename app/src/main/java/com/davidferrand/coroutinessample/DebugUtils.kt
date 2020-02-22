package com.davidferrand.coroutinessample

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

fun log(s: String, t: Throwable? = null) {
    val tag = "SHITSHOW"
    val msg = "[Thread:${Thread.currentThread().name}] $s"

    if (t != null) {
        Log.v(tag, msg, t)
    } else {
        Log.v(tag, msg)
    }
}

suspend fun <T> log(s: String, block: suspend () -> T): T {
    log("$s: START")
    val startMs = System.currentTimeMillis()
    try {
        return block()
    } finally {
        log("$s: FINISHED in ${System.currentTimeMillis() - startMs}ms")
    }
}

interface DescribableResource {
    fun describeStatus()
}

class StatusLogger(private val listener: (Status) -> Unit) {
    fun log(status: Status) {
        listener.invoke(status)
    }

    sealed class Status {
        class ApiStatus(
            val isActive: Boolean,
            val nextResponse: Api.ProgrammableResponse,
            val nextResponseDelay: Long
        ) : Status()

        class RamStatus(val isActive: Boolean, val description: String?) : Status()
        class DiskStatus(val isActive: Boolean, val description: String?) : Status()
    }
}

fun Date.formatAsTime() = SimpleDateFormat.getTimeInstance().format(this)
fun Long.formatAsTime() = SimpleDateFormat.getTimeInstance().format(Date(this))