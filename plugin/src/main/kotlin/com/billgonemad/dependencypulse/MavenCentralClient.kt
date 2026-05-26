package com.billgonemad.dependencypulse

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private const val TIMEOUT_SECONDS = 10L

open class MavenCentralClient(
    private val baseUrl: String = "https://search.maven.org",
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    open fun fetchSignals(
        group: String,
        artifact: String,
        version: String,
    ): MavenSignals? {
        val latestDoc =
            fetchDoc(
                "$baseUrl/solrsearch/select?q=g:$group+AND+a:$artifact&rows=1&wt=json",
            )
        val latestVersion = latestDoc?.latestVersion
        val latestTimestamp = latestDoc?.timestamp

        return if (latestVersion != null && latestTimestamp != null) {
            val currentDoc =
                fetchDoc(
                    "$baseUrl/solrsearch/select?q=g:$group+AND+a:$artifact+AND+v:$version&rows=1&core=gav&wt=json",
                )
            MavenSignals(
                latestVersion = latestVersion,
                latestReleaseDate = Instant.ofEpochMilli(latestTimestamp),
                currentVersionDate = currentDoc?.timestamp?.let { Instant.ofEpochMilli(it) },
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
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return json
            .decodeFromString<SolrSearchResponse>(response.body())
            .response.docs
            .firstOrNull()
    }
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
