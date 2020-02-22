package com.davidferrand.coroutinessample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.properties.Delegates

class Api(val statusLogger: StatusLogger) : DescribableResource {
    // TODO reuse inflight
    //  https://medium.com/@appmattus/caching-made-simple-on-android-d6e024e3726b
    //  ConcurrencyHelpers.kt

    private var latestId = 0

    var nextResponse: ProgrammableResponse = ProgrammableResponse.SUCCEED
    var nextResponseDelay: Long = 10_000L

    private var activeJobCount by Delegates.observable(0) { _, _, _ -> describeStatus() }

    override fun describeStatus() {
        statusLogger.log(
            StatusLogger.Status.ApiStatus(
                isActive = activeJobCount > 0,
                nextResponse = nextResponse,
                nextResponseDelay = nextResponseDelay
            )
        )
    }

    enum class ProgrammableResponse {
        SUCCEED, FAIL
    }

    suspend fun fetch(): Data {
        // TODO share resources (network call)

        return withContext(Dispatchers.IO) {
            // TODO apparently no need to use withContext() when using Retrofit

            val processingResponse = nextResponse
            val processingResponseDelay = nextResponseDelay

            activeJobCount++
            try {
                log("long api.fetch() operation: will $processingResponse in ${processingResponseDelay}ms") {
                    Thread.sleep(processingResponseDelay)

                    if (processingResponse == ProgrammableResponse.SUCCEED) {
                        Data(
                            id = ++latestId,
                            expiresAtMs = System.currentTimeMillis() + 60_000
                        )

                    } else {
                        throw IOException("Network error")
                    }
                }
            } finally {
                activeJobCount--
            }
        }
    }
}