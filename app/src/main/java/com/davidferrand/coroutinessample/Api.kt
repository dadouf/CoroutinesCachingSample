package com.davidferrand.coroutinessample

import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit

class Api {
    val fetchAction = Action("api.fetch")

    private val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://jsonplaceholder.typicode.com/")
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .client(OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    fetchAction.delayMs?.let { Thread.sleep(it) }

                    if (fetchAction.nextResult == ProgrammableAction.NextResult.SUCCEED) {
                        chain.proceed(chain.request())
                    } else {
                        // Even after throwing an exception here, OkHttp will retry the call in
                        // some conditions, see [OkHttpClient.Builder.retryOnConnectionFailure]
                        throw IOException("Network error")
                    }
                }
                .addNetworkInterceptor(
                    HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY)
                )
                .build()
            )
            .build().create(ApiService::class.java)
    }

    suspend fun fetch(): Data {
        // TODO reuse inflight
        //  https://medium.com/@appmattus/caching-made-simple-on-android-d6e024e3726b
        //  ConcurrencyHelpers.kt
        // TODO but also represent that there might be calls with various urls/bodies.
        //   we want to share the exact ones, or do we...?

        return logSuspending("${fetchAction.tag} operation") {
            fetchAction.activityCount++
            try {
                val randomId = (1..100).random()

                val model = service.getPost(randomId)
                mapModel(model)
            } finally {
                fetchAction.activityCount--
            }
        }
    }

    private suspend fun mapModel(model: DataModel): Data = withContext(Dispatchers.Default) {
        // Artificially block for 1s to simulate a heavy mapping operation. withContext() ensures
        // this runs in the background REGARDLESS of where the caller calls this from.

        logBlocking("long mapModel operation") {
            Thread.sleep(3_000)
            Data(
                id = model.id,
                expiresAtMs = System.currentTimeMillis() + 60_000
            )
        }
    }
}

interface ApiService {
    @GET("/posts/{id}")
    suspend fun getPost(@Path(value = "id") id: Int): DataModel
}

/** See https://jsonplaceholder.typicode.com/posts/1 */
data class DataModel(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String
)