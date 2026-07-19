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

internal fun newDefaultHttpClient(): HttpClient =
    HttpClient
        .newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

internal sealed class SafeGetResult {
    data class Success(
        val response: HttpResponse<String>,
    ) : SafeGetResult()

    data class Failure(
        val retryable: Boolean,
    ) : SafeGetResult()
}

internal fun SafeGetResult.orNull(): HttpResponse<String>? = (this as? SafeGetResult.Success)?.response

internal fun safeGet(
    httpClient: HttpClient,
    url: String,
    configureRequest: HttpRequest.Builder.() -> Unit = {},
): SafeGetResult =
    try {
        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS))
                .GET()
        requestBuilder.configureRequest()
        SafeGetResult.Success(httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString()))
    } catch (ignored: IllegalArgumentException) {
        SafeGetResult.Failure(retryable = false)
    } catch (ignored: IOException) {
        SafeGetResult.Failure(retryable = true)
    } catch (ignored: InterruptedException) {
        Thread.currentThread().interrupt()
        SafeGetResult.Failure(retryable = false)
    }
