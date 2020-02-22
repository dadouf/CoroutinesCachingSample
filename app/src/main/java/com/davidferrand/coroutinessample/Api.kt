package com.davidferrand.coroutinessample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

class Api(onActiveJobCountChangedObserver: (property: KProperty<*>, oldValue: Int, newValue: Int) -> Unit) {
    // TODO reuse inflight
    //  https://medium.com/@appmattus/caching-made-simple-on-android-d6e024e3726b

    private var latestId = 0

    var nextResponse: ProgrammableResponse = ProgrammableResponse.SUCCEED
    var nextResponseDelay: Long = 10_000

    var activeJobCount: Int by Delegates.observable(
        0,
        onActiveJobCountChangedObserver
    )
        private set

    enum class ProgrammableResponse {
        SUCCEED, FAIL
    }

    suspend fun fetch(): Data {
        return withContext(Dispatchers.IO) {
            // TODO apparently no need to use withContext() when using Retrofit

            val processingResponse = nextResponse
            val processingResponseDelay = nextResponseDelay

            log("long api.fetch() operation: will $processingResponse in ${processingResponseDelay}ms") {
                Thread.sleep(processingResponseDelay)

                if (processingResponse == ProgrammableResponse.SUCCEED) {
                    Data(
                        ++latestId,
                        expiresAtMs = Date(System.currentTimeMillis() + 60_000)
                    )

                } else {
                    throw IOException("Network error")
                }
            }
        }
    }
}