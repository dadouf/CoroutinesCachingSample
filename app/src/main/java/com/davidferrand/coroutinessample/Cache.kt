package com.davidferrand.coroutinessample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

abstract class Cache(
    private val tag: String,
    val readDelayMs: Long,
    val writeDelayMs: Long,
    onDataChangedObserver: (property: KProperty<*>, oldValue: Data?, newValue: Data?) -> Unit,
    onActiveJobCountChangedObserver: (property: KProperty<*>, oldValue: Int, newValue: Int) -> Unit
) {
    private var cachedData: Data? by Delegates.observable(
        null,
        onDataChangedObserver
    )
    var activeJobCount: Int by Delegates.observable(
        0,
        onActiveJobCountChangedObserver
    )
        private set

    suspend fun read() = withContext(Dispatchers.IO) {
        log("long $tag.read() operation") {
            activeJobCount++
            try {
                Thread.sleep(readDelayMs)
                cachedData
            } finally {
                activeJobCount--
            }
        }
    }

    suspend fun write(data: Data) = withContext(Dispatchers.IO) {
        log("long $tag.write() operation") {
            activeJobCount++
            try {
                Thread.sleep(writeDelayMs)
                cachedData = data
            } finally {
                activeJobCount--
            }
        }
    }
}

class RamCache: Cache("ramCache", readDelayMs = 10, writeDelayMs = 20)

class DiskCache : Cache("disk") {

}