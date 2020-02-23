package com.davidferrand.coroutinessample

import kotlinx.coroutines.*

class Agent(
    private val local: Cache,
    private val remote: Api
) {
    // These two scopes are introduced for operation that should NOT be cancelled
    // by the user leaving the screen/app
    private val apiReadScope =
        CoroutineScope(Dispatchers.Default) // TODO maybe I need exception handlers
    private val cachesWriteScope = CoroutineScope(Dispatchers.Default)

    /**
     * @return the best available data and its source
     */
    suspend fun getData(): Pair<Data, String>? = coroutineScope {
        // TODO <-- do i need the coroutineScope? why?
        val localData = local.read()

        if (localData != null && localData.isFresh()) {
            log("ramData is fresh, using it")
            return@coroutineScope localData to "LOCAL"
        }

        try {
            // Launch the job in a separate scope so that it doesn't get cancelled
            // on timeout. TODO test it
            val fetchJob = apiReadScope.async {
                remote.fetch()
                // TODO introduce retrofit
                // TODO represent the parsing as a long operation on non-IO dispatcher
            }

            cachesWriteScope.launch {
                try {
                    val networkDataForCaching = fetchJob.await()
                    launch { local.write(networkDataForCaching) }
                } catch (t: Throwable) {
                    log("Error with fetchJob - side effect", t)

                    // Catch exception otherwise the app crashes (unhandled)
                    throw t
                }
            }

            return@coroutineScope withTimeout(5_000) { fetchJob.await() } to "API"

            // FIXME network error with delay set to 3_000 crashes the app!
            // FIXME appears fixed - but can't recover after a network error

        } catch (t: Throwable) {
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

