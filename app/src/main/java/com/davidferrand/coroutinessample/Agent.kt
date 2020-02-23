package com.davidferrand.coroutinessample

import kotlinx.coroutines.*

class Agent(
    private val local: Cache,
    private val remote: Api
) {
    // These two scopes are introduced for operation that should NOT be cancelled
    // by the user leaving the screen/app
    // TODO maybe I need exception handlers
    private val apiReadScope = CoroutineScope(Dispatchers.Default)
    private val cacheWriteScope = CoroutineScope(Dispatchers.Default)

    /**
     * @return the best available data and its source
     */
    suspend fun getData(): Pair<Data, String>? = coroutineScope {
        // TODO <-- do i need the coroutineScope? why?

        try {
            val localData = local.read()

            if (localData != null && localData.isFresh()) {
                log("ramData is fresh, using it")
                return@coroutineScope localData to "LOCAL"
            }
        } catch (t: Throwable) {
            log("Error thrown by local.read()", t)
            throw t
        }

        try {
            // Launch the job in a separate scope so that it doesn't get cancelled
            // on timeout. TODO test it
            val fetchJob = apiReadScope.async {
                try {
                    remote.fetch()
                } catch (t: Throwable) {
                    log("Error thrown by remote.fetch() in apiReadScope")
                    throw t
                }
                // TODO introduce retrofit
                // TODO represent the parsing as a long operation on non-IO dispatcher
            }

            cacheWriteScope.launch {
                try {
                    val networkDataForCaching = fetchJob.await()
                    local.write(networkDataForCaching)
                } catch (t: Throwable) {
                    log("Error thrown when trying to save network result in cachesWriteScope", t)
                    throw t
                }
            }

            return@coroutineScope withTimeout(5_000) {
                try {
                    fetchJob.await()
                } catch (t: Throwable) {
                    log("Error thrown by fetchJob.await() in timeout scope", t)
                    throw t
                }
            } to "API"

            // FIXME network error with delay set to 3_000 crashes the app!
            // FIXME appears fixed - but can't recover after a network error

        } catch (t: Throwable) {
            log("Error thrown by remote.fetch() or some resulting code", t)
            throw t
//            log("Error with fetchJob - main branch", t)
//
//            // Fallback to LOCAL data if possible
//            return@coroutineScope localData?.let { it to "LOCAL" }

            // FIXME if we fallback with diskData, we should write it to RAM
        }

        // FIXME case that fails:
        // stale data
        // network will succeed post timeout
        // get data
        // display says fail even though it could be using the local as fallback
    }

    fun refresh() {
    }
}

