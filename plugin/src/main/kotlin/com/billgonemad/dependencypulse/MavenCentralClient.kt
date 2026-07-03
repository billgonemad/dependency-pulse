package com.billgonemad.dependencypulse

import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private const val MAX_RETRIES = 3

// Versions fetched per artifact (newest first). Must exceed the number of
// pre-releases published more recently than the latest stable, otherwise that
// stable falls outside the window and selectLatest falls back to a pre-release.
private const val VERSION_FETCH_LIMIT = 100
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
    private val urlCache = HashMap<String, List<VersionEntry>>()

    open fun fetchSignals(
        group: String,
        artifact: String,
        currentVersion: String,
    ): MavenSignals? {
        val g = encode(group)
        val a = encode(artifact)
        val url =
            "$baseUrl/solrsearch/select?q=g:$g+AND+a:$a" +
                "&core=gav&rows=$VERSION_FETCH_LIMIT&sort=timestamp+desc&wt=json"
        val selected = selectLatest(fetchVersions(url), currentVersion) ?: return null
        return MavenSignals(
            latestVersion = selected.version,
            latestReleaseDate = Instant.ofEpochMilli(selected.timestamp),
        )
    }

    private fun fetchVersions(url: String): List<VersionEntry> {
        urlCache[url]?.let { return it }
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS))
                .GET()
                .build()
        var attempt = 0
        while (true) {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() == HTTP_OK -> {
                    val versions =
                        DEFAULT_JSON
                            .decodeFromString<SolrSearchResponse>(response.body())
                            .response.docs
                            .mapNotNull { it.toVersionEntry() }
                    urlCache[url] = versions
                    return versions
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
    val v: String? = null,
    val timestamp: Long? = null,
) {
    fun toVersionEntry(): VersionEntry? = if (v != null && timestamp != null) VersionEntry(v, timestamp) else null
}
