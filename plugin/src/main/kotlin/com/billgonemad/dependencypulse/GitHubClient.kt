package com.billgonemad.dependencypulse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Instant

private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
private const val HEADER_RATE_LIMIT_RESET = "X-RateLimit-Reset"
private const val HEADER_RETRY_AFTER = "Retry-After"
private const val DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS = 60L
private const val MAX_RETRIES = 3
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
private val RETRYABLE_CODES =
    setOf(
        HTTP_INTERNAL_SERVER_ERROR,
        HTTP_BAD_GATEWAY,
        HTTP_SERVICE_UNAVAILABLE,
        HTTP_GATEWAY_TIMEOUT,
    )

internal interface RateLimitState {
    var limitedUntil: Instant?

    companion object {
        fun local(): RateLimitState =
            object : RateLimitState {
                @Volatile
                override var limitedUntil: Instant? = null
            }
    }
}

open class GitHubClient internal constructor(
    private val baseUrl: String = "https://api.github.com",
    private val httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val token: String? = null,
    private val retryDelayMs: Long = 1_000L,
    private val rateLimitState: RateLimitState = RateLimitState.local(),
) {
    open fun fetchSignals(ownerRepo: String): GitHubSignals {
        val repoInfo =
            fetchRepoInfo(ownerRepo)
                ?: return if (isRateLimited()) GitHubSignals.RateLimited else GitHubSignals.FetchFailed
        val lastCommitDate = fetchLastCommitDate(ownerRepo) ?: repoInfo.pushedAt
        return GitHubSignals.Found(lastCommitDate, repoInfo.archived)
    }

    private fun isRateLimited(): Boolean {
        val limitedUntil = rateLimitState.limitedUntil
        return limitedUntil != null && Instant.now().isBefore(limitedUntil)
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
        if (isRateLimited()) return null
        var attempt = 0
        var result: HttpResponse<String>? = null
        while (true) {
            val outcome =
                safeGet(httpClient, url) {
                    if (token != null) header("Authorization", "Bearer $token")
                }
            val response = outcome.orNull()
            val statusCode = response?.statusCode()
            if (statusCode == HTTP_FORBIDDEN || statusCode == HTTP_TOO_MANY_REQUESTS) checkRateLimit(response)
            result = response
            val networkRetryable = outcome is SafeGetResult.Failure && outcome.retryable
            val statusRetryable = statusCode != null && statusCode in RETRYABLE_CODES
            val canRetry = (networkRetryable || statusRetryable) && attempt < MAX_RETRIES
            if (!canRetry) break
            Thread.sleep(retryDelayMs * (1L shl attempt))
            attempt++
        }
        return result
    }

    private fun checkRateLimit(response: HttpResponse<String>) {
        val remaining = response.headers().firstValue(HEADER_RATE_LIMIT_REMAINING).orElse(null)
        val reset =
            response
                .headers()
                .firstValue(HEADER_RATE_LIMIT_RESET)
                .orElse(null)
                ?.toLongOrNull()
        val retryAfter =
            response
                .headers()
                .firstValue(HEADER_RETRY_AFTER)
                .orElse(null)
                ?.toLongOrNull()
        val cooldownUntil =
            when {
                remaining == "0" && reset != null -> Instant.ofEpochSecond(reset)
                retryAfter != null -> Instant.now().plusSeconds(retryAfter)
                remaining == "0" -> Instant.now().plusSeconds(DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS)
                else -> null
            }
        if (cooldownUntil != null) rateLimitState.limitedUntil = cooldownUntil
    }

    private fun decodeRepoInfo(body: String): RepoInfo? =
        try {
            val dto = DEFAULT_JSON.decodeFromString<RepoResponse>(body)
            RepoInfo(archived = dto.archived, pushedAt = dto.pushedAt?.let(Instant::parse))
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            null
        }

    private fun decodeLastCommitDate(body: String): Instant? =
        try {
            DEFAULT_JSON
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
