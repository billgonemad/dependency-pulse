package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.http.HttpClient
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            GitHubClient(
                baseUrl = "http://${server.hostName}:${server.port}",
            )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test fun `fetches archived flag and last commit date for a resolvable repo`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        val result = client.fetchSignals("owner/repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(Instant.parse("2024-03-20T08:30:00Z"), result.lastCommitDate)
        assertFalse(result.isArchived)
    }

    @Test fun `reports archived repos as archived`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":true,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        val result = client.fetchSignals("owner/repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(true, result.isArchived)
    }

    @Test fun `still reports the archived flag when neither commits nor pushed_at yield a date`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":true,"pushed_at":null}"""),
        )
        server.enqueue(MockResponse().setResponseCode(409))

        val result = client.fetchSignals("owner/repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(true, result.isArchived)
        assertNull(result.lastCommitDate)
    }

    @Test fun `requests the repo endpoint before the commits endpoint`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        client.fetchSignals("owner/repo")

        assertEquals("/repos/owner/repo", server.takeRequest().path)
        assertEquals("/repos/owner/repo/commits?per_page=1", server.takeRequest().path)
    }

    @Test fun `sends a Bearer Authorization header when a token is configured`() {
        client =
            GitHubClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                token = "secret-token",
            )
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        client.fetchSignals("owner/repo")

        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `omits the Authorization header when no token is configured`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        client.fetchSignals("owner/repo")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `falls back to repo pushed_at when the commits endpoint has no commits`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.fetchSignals("owner/repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(Instant.parse("2024-01-15T10:00:00Z"), result.lastCommitDate)
    }

    @Test fun `returns FetchFailed when the repo is not found`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(GitHubSignals.FetchFailed, client.fetchSignals("owner/repo"))
    }

    @Test fun `returns FetchFailed when the server is unreachable`() {
        server.shutdown()

        assertEquals(GitHubSignals.FetchFailed, client.fetchSignals("owner/repo"))
    }

    @Test fun `returns FetchFailed for malformed json`() {
        server.enqueue(MockResponse().setBody("not json"))

        assertEquals(GitHubSignals.FetchFailed, client.fetchSignals("owner/repo"))
    }

    @Test fun `returns FetchFailed when owner-repo produces an invalid uri`() {
        assertEquals(GitHubSignals.FetchFailed, client.fetchSignals("owner/repo with spaces"))
    }

    @Test fun `returns RateLimited when the primary rate limit is exhausted`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"),
        )

        assertEquals(GitHubSignals.RateLimited, client.fetchSignals("owner/repo"))
    }

    @Test fun `short-circuits later calls once the primary rate limit is hit`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"),
        )

        client.fetchSignals("owner/repo")
        val second = client.fetchSignals("another/repo")

        assertEquals(GitHubSignals.RateLimited, second)
        assertEquals(1, server.requestCount)
    }

    @Test fun `short-circuits later calls when a Retry-After header signals a secondary rate limit`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Retry-After", "60"),
        )

        client.fetchSignals("owner/repo")
        client.fetchSignals("another/repo")

        assertEquals(1, server.requestCount)
    }

    @Test fun `short-circuits later calls when a 429 response signals the rate limit is exhausted`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("X-RateLimit-Remaining", "0"),
        )

        client.fetchSignals("owner/repo")
        client.fetchSignals("another/repo")

        assertEquals(1, server.requestCount)
    }

    @Test fun `a plain 403 without rate-limit headers does not short-circuit later calls`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        val first = client.fetchSignals("owner/repo")
        val second = client.fetchSignals("another/repo")

        assertEquals(GitHubSignals.FetchFailed, first)
        assertIs<GitHubSignals.Found>(second)
    }

    @Test fun `retries a transient 503 from the commits endpoint before falling back`() {
        client =
            GitHubClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                retryDelayMs = 0L,
            )
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        val result = client.fetchSignals("owner/repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(Instant.parse("2024-03-20T08:30:00Z"), result.lastCommitDate)
        assertEquals(3, server.requestCount)
    }

    @Test fun `exhausts retries on a persistent 503 and falls back to pushed_at`() {
        client =
            GitHubClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                retryDelayMs = 0L,
            )
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

        val result = client.fetchSignals("owner/repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(Instant.parse("2024-01-15T10:00:00Z"), result.lastCommitDate)
        assertEquals(5, server.requestCount)
    }

    @Test fun `does not retry a 403 rate-limit response`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"),
        )

        client.fetchSignals("owner/repo")

        assertEquals(1, server.requestCount)
    }

    @Test fun `follows redirects for renamed repos`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", "http://${server.hostName}:${server.port}/repos/new-owner/new-repo"),
        )
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse().setBody("""[{"commit":{"committer":{"date":"2024-03-20T08:30:00Z"}}}]"""),
        )

        val result = client.fetchSignals("old-owner/old-repo")

        assertIs<GitHubSignals.Found>(result)
        assertEquals(Instant.parse("2024-03-20T08:30:00Z"), result.lastCommitDate)
    }

    @Test fun `two clients sharing the same RateLimitState both see a rate limit tripped by either`() {
        val sharedState = RateLimitState.local()
        val clientA =
            GitHubClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                rateLimitState = sharedState,
            )
        val clientB =
            GitHubClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                rateLimitState = sharedState,
            )
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"),
        )

        val fromA = clientA.fetchSignals("owner/repo")
        val fromB = clientB.fetchSignals("another/repo")

        assertEquals(GitHubSignals.RateLimited, fromA)
        assertEquals(GitHubSignals.RateLimited, fromB)
        assertEquals(1, server.requestCount)
        assertTrue(sharedState.limited)
    }
}
