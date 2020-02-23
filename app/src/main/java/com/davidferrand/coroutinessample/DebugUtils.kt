package com.davidferrand.coroutinessample

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates

fun log(s: String, t: Throwable? = null) {
    val tag = "SHITSHOW"
    val msg = "[Thread:${Thread.currentThread().name}] $s"

    if (t != null) {
        Log.d(tag, msg, t)
    } else {
        Log.d(tag, msg)
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

interface DescribableContents {
    fun describeContents(): String?
}

interface ProgrammableAction {
    var delayMs: Long?
    var nextResult: NextResult

    enum class NextResult {
        SUCCEED, FAIL
    }
}

interface WatchableAction {
    var onActivityChange: (Boolean) -> Unit
    var activityCount: Int
    val isBusy: Boolean
        get() = activityCount > 0
}

class Action(val tag: String = "action@${UUID.randomUUID().toString().take(3)}") :
    ProgrammableAction, WatchableAction {
    override var delayMs: Long? = null
        set(value) {
            field = value
            log("$tag.delayMs = $value")
        }
    override var nextResult: ProgrammableAction.NextResult =
        ProgrammableAction.NextResult.SUCCEED
        set(value) {
            field = value
            log("$tag.nextResult = $value")
        }

    override var onActivityChange: (Boolean) -> Unit = {}

    override var activityCount: Int by Delegates.observable(0) { _, _, newValue ->
        onActivityChange.invoke(newValue > 0)
    }
}

fun Date.formatAsTime() = SimpleDateFormat.getTimeInstance().format(this)
fun Long.formatAsTime() = SimpleDateFormat.getTimeInstance().format(Date(this))