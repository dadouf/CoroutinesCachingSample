package com.davidferrand.coroutinessample

import android.util.Log

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