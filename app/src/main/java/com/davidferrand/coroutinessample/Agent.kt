package com.davidferrand.coroutinessample

import kotlinx.coroutines.*

class Agent(
    private val local: Cache,
    private val remote: Api
) {
    /*
     * These scopes are introduced because the remote.fetch operation (and subsequent cache write)
     * should NOT be cancelled when getData() is cancelled (by the user leaving the screen/app)
     */
    private val apiReadScope = CoroutineScope(SupervisorJob())
    private val cacheWriteScope = CoroutineScope(SupervisorJob())

    private val getDataTimeoutMs: Long = 5_000

    private val controlledRunnerForFetchData = ControlledRunner<Data>()

    /**
     * @return the best available data and its source
     */
    suspend fun getData(): Pair<Data, String>? {
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
            // Launch the fetch job in a separate scope so that it doesn't get cancelled on timeout.
            // Any error within the async{} block:
            //   (1) is thrown when await() is called, so we are catching it there
            //   (2) does NOT render the whole scope invalid thanks to SupervisorJob(): we can use it later
            val survivingFetchJob = apiReadScope.async {

                // Use a controlledRunner to allow only one fetch+writeData at a time:
                // We want to reuse in-flight network requests BUT ALSO write the caches once
                // for a single actual network request.
                controlledRunnerForFetchData.joinPreviousOrRun {
                    val apiResult = remote.fetch()

                    // Launch the cache write job in a separate scope so we can continue with the main flow immediately
                    cacheWriteScope.launch {
                        try {
                            local.write(apiResult)
                        } catch (t: Throwable) {
                            log(
                                "Error thrown by fetchJob.await() in cacheWriteScope. CAUGHT, will ignore.",
                                t
                            )
                        }
                    }

                    apiResult
                }
            }

            // getDataTimeoutMs is TOTAL: if it's set to 5s and I've waited 2s for disk data
            // already, only allow the remaining 3s for the network.
            val elapsedSinceRequestStartMs = System.currentTimeMillis() - requestStartMs
            return withTimeout(getDataTimeoutMs - elapsedSinceRequestStartMs) { survivingFetchJob.await() } to "API"

        } catch (t: Throwable) {
            log(
                "Error thrown by fetchJob.await() in getData(). CAUGHT, will try to fallback to local data",
                t
            )
            return localData?.let { it to "LOCAL" }
        }
    }
}

