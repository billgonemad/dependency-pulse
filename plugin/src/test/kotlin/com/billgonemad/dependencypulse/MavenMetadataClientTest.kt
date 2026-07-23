package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenMetadataClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MavenMetadataClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            MavenMetadataClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                retryDelayMs = 0L,
            )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun metadataBody(
        latest: String,
        vararg versions: String,
    ): String {
        val versionTags = versions.joinToString(separator = "") { "<version>$it</version>" }
        return "<metadata><versioning><latest>$latest</latest><versions>$versionTags</versions></versioning></metadata>"
    }

    private fun pomResponse(lastModified: String = "Tue, 25 Feb 2025 16:43:14 GMT"): MockResponse =
        MockResponse().setBody("<project></project>").setHeader("Last-Modified", lastModified)

    @Test fun `returns MavenSignals when artifact found`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.15", "2.0.16")))
        server.enqueue(pomResponse("Wed, 31 Jul 2024 00:00:00 GMT"))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(Instant.parse("2024-07-31T00:00:00Z"), result.latestReleaseDate)
    }

    @Test fun `returns null when artifact not found on Central`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.fetchSignals("com.example", "nonexistent", "1.0.0")

        assertNull(result)
    }

    @Test fun `throws when server is unreachable`() {
        server.shutdown()

        assertFailsWith<Exception> {
            client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        }
    }

    // Uses FlakyHttpClient rather than a MockWebServer SocketPolicy: the JDK
    // HttpClient silently retries a connection reset on an established
    // connection on its own, so a socket-policy-based disconnect never
    // reaches safeGet's retry logic at all (verified empirically — it's not
    // a hypothetical). A fake HttpClient that throws IOException directly
    // is the only way to deterministically exercise that path; same
    // technique GitHubClientTest.kt already uses for the same reason.
    @Test fun `retries once on connection-level failure and succeeds on the subsequent request`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(pomResponse())
        val flakyClient =
            MavenMetadataClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = MavenMetadataFlakyHttpClient(HttpClient.newHttpClient(), failuresRemaining = 1),
                retryDelayMs = 0L,
            )

        val result = flakyClient.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(2, server.requestCount)
    }

    @Test fun `throws IOException immediately on non-retryable 4xx response`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val ex =
            assertFailsWith<IOException> {
                client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
            }
        assertTrue(ex.message?.contains("403") == true)
        assertEquals(1, server.requestCount)
    }

    @Test fun `throws IOException after exhausting all retries on persistent 429`() {
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(429))
        }

        assertFailsWith<IOException> {
            client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        }

        assertEquals(4, server.requestCount)
    }

    @Test fun `retries once on 429 and returns result on subsequent 200`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(pomResponse())

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(3, server.requestCount)
    }

    @Test fun `retries on 503 during the Last-Modified lookup and returns result on subsequent 200`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(pomResponse())

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(3, server.requestCount)
    }

    @Test fun `throws when the selected version's POM is missing`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(404))

        assertFailsWith<IOException> {
            client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        }
    }

    @Test fun `throws when Last-Modified header is missing`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setBody("<project></project>"))

        assertFailsWith<IOException> {
            client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        }
    }

    @Test fun `caches metadata and last-modified responses per URL`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(pomResponse())

        client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        client.fetchSignals("org.slf4j", "slf4j-api", "2.0.0")

        assertEquals(2, server.requestCount)
    }

    @Test fun `explicit baseUrl argument overrides the constructor default`() {
        val secondServer = MockWebServer()
        secondServer.start()
        secondServer.enqueue(MockResponse().setBody(metadataBody("9.9.9", "9.9.9")))
        secondServer.enqueue(pomResponse("Fri, 01 Aug 2025 00:00:00 GMT"))

        val result =
            client.fetchSignals(
                "org.slf4j",
                "slf4j-api",
                "1.0.0",
                baseUrl = "http://${secondServer.hostName}:${secondServer.port}",
            )

        assertNotNull(result)
        assertEquals("9.9.9", result.latestVersion)
        assertEquals(0, server.requestCount)
        assertEquals(2, secondServer.requestCount)
        secondServer.shutdown()
    }

    @Test fun `caches independently per baseUrl for the same group and artifact`() {
        val secondServer = MockWebServer()
        secondServer.start()
        server.enqueue(MockResponse().setBody(metadataBody("1.0.0", "1.0.0")))
        server.enqueue(pomResponse("Mon, 01 Jan 2024 00:00:00 GMT"))
        secondServer.enqueue(MockResponse().setBody(metadataBody("2.0.0", "2.0.0")))
        secondServer.enqueue(pomResponse("Tue, 01 Jul 2025 00:00:00 GMT"))

        val first = client.fetchSignals("org.slf4j", "slf4j-api", "0.9.0")
        val second =
            client.fetchSignals(
                "org.slf4j",
                "slf4j-api",
                "0.9.0",
                baseUrl = "http://${secondServer.hostName}:${secondServer.port}",
            )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals("1.0.0", first.latestVersion)
        assertEquals("2.0.0", second.latestVersion)
        secondServer.shutdown()
    }

    @Test fun `selects latest stable from a mixed version list`() {
        server.enqueue(MockResponse().setBody(metadataBody("5.0.0-alpha.16", "4.11.0", "4.12.0", "5.0.0-alpha.16")))
        server.enqueue(pomResponse())

        val result = client.fetchSignals("com.squareup.okhttp3", "okhttp", "4.12.0")

        assertNotNull(result)
        assertEquals("4.12.0", result.latestVersion)
    }

    @Test fun `derives latest from last version when latest tag is absent`() {
        server.enqueue(
            MockResponse().setBody(
                "<metadata><versioning><versions><version>2.7.1</version>" +
                    "<version>2.7.6</version></versions></versioning></metadata>",
            ),
        )
        server.enqueue(pomResponse("Tue, 13 Oct 2009 23:35:00 GMT"))

        val result = client.fetchSignals("antlr", "antlr", "2.7.7")

        assertNotNull(result)
        assertEquals("2.7.6", result.latestVersion)
        assertEquals(Instant.parse("2009-10-13T23:35:00Z"), result.latestReleaseDate)
    }

    @Test fun `throws when both latest and versions are absent`() {
        server.enqueue(MockResponse().setBody("<metadata><versioning></versioning></metadata>"))

        val ex =
            assertFailsWith<IOException> {
                client.fetchSignals("com.example", "empty-metadata", "1.0.0")
            }
        assertTrue(ex.message?.contains("latest") == true)
    }
}

// Wraps a real HttpClient and throws IOException directly for the first
// `failuresRemaining` calls to send() before delegating normally — the
// only reliable way to deterministically exercise safeGet's retry path
// (see the comment on the connection-level-failure test above). Same
// fixture GitHubClientTest.kt uses for the same reason; kept as its own
// private copy here rather than shared, matching this codebase's existing
// per-file duplication of small test fixtures and retry constants. Named
// distinctly (MavenMetadataFlakyHttpClient rather than FlakyHttpClient) to
// avoid a JVM class redeclaration clash with GitHubClientTest.kt's own
// private FlakyHttpClient — Kotlin top-level `private` classes are still
// compiled to a named class in the package's namespace, so two files in
// the same package cannot both declare one with an identical name.
private class MavenMetadataFlakyHttpClient(
    private val delegate: HttpClient,
    private var failuresRemaining: Int,
) : HttpClient() {
    override fun cookieHandler() = delegate.cookieHandler()

    override fun connectTimeout() = delegate.connectTimeout()

    override fun followRedirects(): HttpClient.Redirect = delegate.followRedirects()

    override fun proxy() = delegate.proxy()

    override fun sslContext() = delegate.sslContext()

    override fun sslParameters() = delegate.sslParameters()

    override fun authenticator() = delegate.authenticator()

    override fun version(): HttpClient.Version = delegate.version()

    override fun executor() = delegate.executor()

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IOException("simulated connection failure")
        }
        return delegate.send(request, responseBodyHandler)
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ) = delegate.sendAsync(request, responseBodyHandler)

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ) = delegate.sendAsync(request, responseBodyHandler, pushPromiseHandler)
}
