package com.billgonemad.dependencypulse

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private const val TIMEOUT_SECONDS = 10L
private const val HTTP_OK = 200
private const val MAX_RETRIES = 3
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
private val RETRYABLE_CODES =
    setOf(
        HTTP_TOO_MANY_REQUESTS,
        HTTP_INTERNAL_SERVER_ERROR,
        HTTP_BAD_GATEWAY,
        HTTP_SERVICE_UNAVAILABLE,
        HTTP_GATEWAY_TIMEOUT,
    )

open class MavenCentralClient(
    private val baseUrl: String = "https://search.maven.org",
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
    private val retryDelayMs: Long = 1_000L,
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun fetchSignals(
        group: String,
        artifact: String,
    ): MavenSignals? {
        val g = encode(group)
        val a = encode(artifact)
        val latestDoc = fetchDoc("$baseUrl/solrsearch/select?q=g:$g+AND+a:$a&rows=1&wt=json")
        val latestVersion = latestDoc?.latestVersion
        val latestTimestamp = latestDoc?.timestamp

        return if (latestVersion != null && latestTimestamp != null) {
            MavenSignals(
                latestVersion = latestVersion,
                latestReleaseDate = Instant.ofEpochMilli(latestTimestamp),
            )
        } else {
            null
        }
    }

    private fun fetchDoc(url: String): SolrDoc? {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
        var attempt = 0
        while (true) {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() == HTTP_OK -> {
                    return json
                        .decodeFromString<SolrSearchResponse>(response.body())
                        .response.docs
                        .firstOrNull()
                }

                response.statusCode() in RETRYABLE_CODES && attempt < MAX_RETRIES -> {
                    Thread.sleep(retryDelayMs * (1L shl attempt))
                    attempt++
                }

                else -> {
                    throw IOException("Maven Central returned HTTP ${response.statusCode()} for $url")
                }
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

@Serializable
private data class SolrSearchResponse(
    val response: SolrResponseBody,
)

@Serializable
private data class SolrResponseBody(
    val docs: List<SolrDoc>,
)

@Serializable
private data class SolrDoc(
    val latestVersion: String? = null,
    val timestamp: Long? = null,
)
