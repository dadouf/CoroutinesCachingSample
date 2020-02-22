package com.davidferrand.coroutinessample

import kotlinx.coroutines.*

class Agent(
    private val ram: Cache,
    private val disk: Cache,
    private val api: Api
) {
    // These two scopes are introduced for operation that should NOT be cancelled
    // by the user leaving the screen/app
    private val apiReadScope = CoroutineScope(Dispatchers.Default)
    private val cachesWriteScope = CoroutineScope(Dispatchers.Default)

    /**
     * @return the best available data and its source
     */
    suspend fun getData(): Pair<Data, String>? = coroutineScope { // TODO <-- do i need the coroutineScope? why?
        val ramData = ram.read()

        if (ramData != null && ramData.isFresh()) {
            log("ramData is fresh, using it")
            return@coroutineScope ramData to "RAM"
        }

        val diskData = disk.read()
        if (diskData != null && diskData.isFresh()) {
            log("diskData is fresh, using it")

            cachesWriteScope.launch { ram.write(diskData) }
            return@coroutineScope diskData to "DISK"
        }

        try {
            val fetchJob = apiReadScope.async {
                api.fetch()
                // TODO introduce retrofit
                // TODO represent the parsing as a long operation on non-IO dispatcher
            }

            cachesWriteScope.launch {
                val networkDataForCaching = fetchJob.await()
                launch { ram.write(networkDataForCaching) }
                launch { disk.write(networkDataForCaching) }
            }

            return@coroutineScope withTimeout(5_000) { fetchJob.await() } to "API"

            // FIXME network error with delay set to 3_000 crashes the app!

        } catch (t: Throwable) {
            log("Got an error", t)

            // Fallback to RAM or DISK if possible
            return@coroutineScope ramData?.let { it to "RAM" }
                ?: diskData?.let { it to "DISK" }

            // FIXME if we fallback with diskData, we should write it to RAM
        }
    }

    fun refresh() {
    }
}

