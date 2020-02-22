package com.davidferrand.coroutinessample

import kotlinx.coroutines.*

class Agent(
    private val ram: Cache,
    private val disk: Cache,
    private val api: Api
) {
    private val sideEffectsScope = CoroutineScope(Dispatchers.Default)

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

        try {
            val fetchJob = GlobalScope.async {
                api.fetch()
                // TODO represent the parsing as a long operation on non-IO dispatcher
            }

            sideEffectsScope.launch {
                val networkDataForCaching = fetchJob.await()
                sideEffectsScope.launch { ram.write(networkDataForCaching) }
                sideEffectsScope.launch { disk.write(networkDataForCaching) }
            }

            delay(5_000) // FIXME this should be a max delay!!!
            return fetchJob.getCompleted() to "API"

            // FIXME network error with delay set to 3_000 crashes the app!

        } catch (t: Throwable) {
            log("Got an error", t)

            // Fallback to RAM or DISK if possible
            return ramData?.let { it to "RAM" }
                ?: diskData?.let { it to "DISK" }

            // FIXME if we fallback with diskData, we should write it to RAM
        }
    }

    fun refresh() {
    }
}

