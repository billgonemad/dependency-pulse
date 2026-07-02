package com.billgonemad.dependencypulse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private const val TIMEOUT_SECONDS = 10L
private const val HTTP_OK = 200
private const val HTTP_FORBIDDEN = 403
private const val HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
private const val HEADER_RETRY_AFTER = "Retry-After"

open class GitHubClient(
    private val baseUrl: String = "https://api.github.com",
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
    private val token: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var rateLimited = false

    open fun fetchSignals(ownerRepo: String): GitHubSignals? {
        val repoInfo = fetchRepoInfo(ownerRepo) ?: return null
        // pushed_at is a repo-level approximation of the last commit date; used when
        // the commits call fails (transient error, or the rate limit was hit on the
        // second request) but the repo endpoint already succeeded.
        val lastCommitDate = fetchLastCommitDate(ownerRepo) ?: repoInfo.pushedAt
        return lastCommitDate?.let { GitHubSignals(it, repoInfo.archived) }
    }

    private fun fetchRepoInfo(ownerRepo: String): RepoInfo? {
        val response = get("$baseUrl/repos/$ownerRepo") ?: return null
        return if (response.statusCode() == HTTP_OK) decodeRepoInfo(response.body()) else null
    }

    private fun fetchLastCommitDate(ownerRepo: String): Instant? {
        val response = get("$baseUrl/repos/$ownerRepo/commits?per_page=1") ?: return null
        return if (response.statusCode() == HTTP_OK) decodeLastCommitDate(response.body()) else null
    }

    private fun get(url: String): HttpResponse<String>? {
        if (rateLimited) return null
        return try {
            val requestBuilder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
            if (token != null) requestBuilder.header("Authorization", "Bearer $token")
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == HTTP_FORBIDDEN) checkRateLimit(response)
            response
        } catch (ignored: IllegalArgumentException) {
            null
        } catch (ignored: IOException) {
            null
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun checkRateLimit(response: HttpResponse<String>) {
        val remaining = response.headers().firstValue(HEADER_RATE_LIMIT_REMAINING).orElse(null)
        val retryAfter = response.headers().firstValue(HEADER_RETRY_AFTER).orElse(null)
        if (remaining == "0" || retryAfter != null) rateLimited = true
    }

    private fun decodeRepoInfo(body: String): RepoInfo? =
        try {
            val dto = json.decodeFromString<RepoResponse>(body)
            RepoInfo(archived = dto.archived, pushedAt = dto.pushedAt?.let(Instant::parse))
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            null
        }

    private fun decodeLastCommitDate(body: String): Instant? =
        try {
            json
                .decodeFromString<List<CommitResponse>>(body)
                .firstOrNull()
                ?.commit
                ?.committer
                ?.date
                ?.let(Instant::parse)
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            null
        }
}

private data class RepoInfo(
    val archived: Boolean,
    val pushedAt: Instant?,
)

@Serializable
private data class RepoResponse(
    val archived: Boolean = false,
    @SerialName("pushed_at") val pushedAt: String? = null,
)

@Serializable
private data class CommitResponse(
    val commit: CommitDetail? = null,
)

@Serializable
private data class CommitDetail(
    val committer: CommitterDetail? = null,
)

@Serializable
private data class CommitterDetail(
    val date: String? = null,
)
