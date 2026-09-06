package com.flex.elefin.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Transport only: tokens/cookies stay on individual requests, never on the shared client. */
object SharedHttpClients {
    val jellyfin: HttpClient by lazy { create(strictStatus = true, coerceValues = false) }
    val authentication: HttpClient by lazy { create(strictStatus = false, coerceValues = false) }
    val jellyseerr: HttpClient by lazy { create(strictStatus = false, coerceValues = true) }

    private fun create(strictStatus: Boolean, coerceValues: Boolean) = HttpClient(Android) {
        expectSuccess = strictStatus
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = coerceValues; encodeDefaults = coerceValues })
        }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        engine { connectTimeout = 10_000; socketTimeout = 15_000 }
    }
}
