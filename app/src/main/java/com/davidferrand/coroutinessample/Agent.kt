package com.davidferrand.coroutinessample

import kotlinx.coroutines.*

class Agent(
    private val local: Cache,
    private val remote: Api
) {
    // These two scopes are introduced for operations that should NOT be cancelled
    // by the user leaving the screen/app
    private val apiReadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cacheWriteScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val getDataTimeoutMs: Long = 5_000

    /**
     * @return the best available data and its source
     */
    suspend fun getData(): Pair<Data, String>? {
        // TODO <-- do i need the coroutineScope? why?

        val requestStartMs = System.currentTimeMillis()

        val localData: Data? =
            try {
                local.read()
            } catch (t: Throwable) {
                log("Error thrown by local.read(). CAUGHT, will return null.", t)
                null
            }

        if (localData != null && localData.isFresh()) {
            log("localData is fresh, using it")
            return localData to "LOCAL"
        }

        try {
            // TODO introduce retrofit
            // TODO represent the parsing as a long operation on non-IO dispatcher (what's good practice?)

            // Launch the job in a separate scope so that it doesn't get cancelled on timeout
            val fetchJob = apiReadScope.async { remote.fetch() }

            cacheWriteScope.launch {
                try {
                    local.write(fetchJob.await())
                } catch (t: Throwable) {
                    log(
                        "Error thrown when trying to save network result in cachesWriteScope. CAUGHT, will ignore.",
                        t
                    )
                }
            }

            // getDataTimeoutMs is TOTAL: if it's set to 5s and I've waited 2s for disk data
            // already, only allow the remaining 3s for the network.
            val elapsedSinceRequestStartMs = System.currentTimeMillis() - requestStartMs
            return withTimeout(getDataTimeoutMs - elapsedSinceRequestStartMs) { fetchJob.await() } to "API"

        } catch (t: Throwable) {
            log("Error thrown by remote.fetch(). CAUGHT, will try to fallback to local data", t)
            return localData?.let { it to "LOCAL" }
        }
    }

    fun refresh() {
        // TODO implement:
        // - we don't need to fetch ALL the data from cache, just check its freshness
        // - if not fresh, then fetch and store in background
        // A simple (non-optimized) version just does getData() but ignores the result
    }
}

