package com.billgonemad.dependencypulse

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal const val HTTP_OK = 200
internal const val DEFAULT_HTTP_TIMEOUT_SECONDS = 10L

internal val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

internal fun safeGet(
    httpClient: HttpClient,
    url: String,
    configureRequest: HttpRequest.Builder.() -> Unit = {},
): HttpResponse<String>? =
    try {
        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS))
                .GET()
        requestBuilder.configureRequest()
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    } catch (ignored: IllegalArgumentException) {
        null
    } catch (ignored: IOException) {
        null
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }
