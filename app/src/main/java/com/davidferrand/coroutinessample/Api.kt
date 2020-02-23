package com.davidferrand.coroutinessample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class Api {
    val fetchAction = Action("api.fetch")

    private var latestId = 0

    suspend fun fetch(): Data {
        // TODO reuse inflight
        //  https://medium.com/@appmattus/caching-made-simple-on-android-d6e024e3726b
        //  ConcurrencyHelpers.kt

        // TODO but also represent that there might be calls with various urls/bodies.
        //   we want to share the exact ones, or do we...?

        return withContext(Dispatchers.IO) {
            // TODO apparently no need to use withContext() when using Retrofit

            val processingResponse = fetchAction.nextResult
            val processingResponseDelay = fetchAction.delayMs

            fetchAction.activityCount++
            try {
                log("long api.fetch() operation: will $processingResponse in ${processingResponseDelay}ms") {
                    processingResponseDelay?.let { Thread.sleep(it) }

                    if (processingResponse == ProgrammableAction.NextResult.SUCCEED) {
                        Data(
                            id = ++latestId,
                            expiresAtMs = System.currentTimeMillis() + 60_000
                        )

                    } else {
                        throw IOException("Network error")
                    }
                }
            } finally {
                fetchAction.activityCount--
            }
        }
    }
}