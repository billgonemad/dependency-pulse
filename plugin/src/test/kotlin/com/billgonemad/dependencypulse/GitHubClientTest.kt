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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
                httpClient = HttpClient.newHttpClient(),
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

        assertNotNull(result)
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

        assertNotNull(result)
        assertEquals(true, result.isArchived)
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

    @Test fun `falls back to repo pushed_at when the commits endpoint fails`() {
        server.enqueue(
            MockResponse().setBody("""{"archived":false,"pushed_at":"2024-01-15T10:00:00Z"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.fetchSignals("owner/repo")

        assertNotNull(result)
        assertEquals(Instant.parse("2024-01-15T10:00:00Z"), result.lastCommitDate)
    }

    @Test fun `returns null when the repo is not found`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(client.fetchSignals("owner/repo"))
    }

    @Test fun `returns null when the server is unreachable`() {
        server.shutdown()

        assertNull(client.fetchSignals("owner/repo"))
    }

    @Test fun `returns null for malformed json`() {
        server.enqueue(MockResponse().setBody("not json"))

        assertNull(client.fetchSignals("owner/repo"))
    }

    @Test fun `returns null when owner-repo produces an invalid uri`() {
        assertNull(client.fetchSignals("owner/repo with spaces"))
    }
}
