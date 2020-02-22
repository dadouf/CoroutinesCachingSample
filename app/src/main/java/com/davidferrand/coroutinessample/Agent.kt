package com.davidferrand.coroutinessample

import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class Agent(
    private val ram: Cache,
    private val disk: Cache,
    private val api: Api
) {
    private val sideEffectsScope = CoroutineScope(Dispatchers.IO)

    /**
     * @return the best available data and its source
     */
    suspend fun getData(): Pair<Data, String>? {
        val ramData = ram.read()

        if (ramData != null && ramData.isFresh()) {
            log("ramData is fresh, using it")
            return ramData to "RAM"
        }

        val diskData = disk.read()
        if (diskData != null && diskData.isFresh()) {
            log("diskData is fresh, using it")

            sideEffectsScope.launch { ram.write(diskData) }
            return diskData to "DISK"
        }

        return try {
            val fetchJob = GlobalScope.async {
                api.fetch()
                // TODO represent the parsing as a long operation on non-IO dispatcher
            }

            sideEffectsScope.launch {
                val networkDataForCaching = fetchJob.await()
                sideEffectsScope.launch { ram.write(networkDataForCaching) }
                sideEffectsScope.launch { disk.write(networkDataForCaching) }
            }

            delay(5_000)
            fetchJob.getCompleted() to "API"

            // FIXME network error with delay set to 3_000 crashes the app!

        } catch (t: Throwable) {
            log("Got an error", t)

            // Fallback to RAM or DISK if possible
            ramData?.let { it to "RAM" }
                ?: diskData?.let { it to "DISK" }
        }
    }

    fun refresh() {
    }
}

data class Data(
    val id: Int,
    val expiresAtMs: Date
) {
    fun isFresh() = expiresAtMs.after(Date())

    override fun toString(): String {
        return "Data(\n\tid=$id,\n\texpiresAt=${SimpleDateFormat.getTimeInstance().format(
            expiresAtMs
        )})"
    }
}