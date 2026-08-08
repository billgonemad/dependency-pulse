package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TAKE_REQUEST_TIMEOUT_SECONDS = 5L

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

    // No body: every .pom request in this file is a HEAD (fetchLastModified never uses GET), and
    // no test here reads the body — a nonempty body on a HEAD response is a known MockWebServer/
    // OkHttp footgun where the body bytes can still be written to the wire despite the method,
    // desyncing a subsequent request reusing the same keep-alive connection (observed as a
    // "Maven repository unreachable" IOException after ~30s on CI, not reproducible locally;
    // a real HTTP server correctly omits the body for HEAD per RFC 7231, so this is a test-double
    // artifact, not a production concern).
    private fun pomResponse(lastModified: String = "Tue, 25 Feb 2025 16:43:14 GMT"): MockResponse =
        MockResponse().setHeader("Last-Modified", lastModified)

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
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.fetchSignals("com.example", "nonexistent", "1.0.0")

        assertNull(result)
        assertEquals(2, server.requestCount)
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

    @Test fun `falls back to currentVersion's own POM when the selected version's POM 404s`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(pomResponse("Mon, 01 Jan 2024 00:00:00 GMT"))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("1.0.0", result.latestVersion)
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), result.latestReleaseDate)
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // maven-metadata.xml
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // selected version's POM (404)
        val fallbackRequest = server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(fallbackRequest)
        assertTrue(fallbackRequest.path?.endsWith("/1.0.0/slf4j-api-1.0.0.pom") == true)
    }

    @Test fun `falls back to currentVersion's own POM when the selected version's POM lacks Last-Modified`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse())
        server.enqueue(pomResponse("Tue, 02 Jan 2024 00:00:00 GMT"))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("1.0.0", result.latestVersion)
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), result.latestReleaseDate)
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // maven-metadata.xml
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // selected version's POM (no header)
        val fallbackRequest = server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(fallbackRequest)
        assertTrue(fallbackRequest.path?.endsWith("/1.0.0/slf4j-api-1.0.0.pom") == true)
    }

    @Test fun `returns null when both the selected version and currentVersion POMs are unavailable`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNull(result)
        assertEquals(3, server.requestCount)
    }

    @Test fun `does not re-fetch when the selected version already equals currentVersion and its POM 404s`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "2.0.16")

        assertNull(result)
        assertEquals(2, server.requestCount)
    }

    @Test fun `falls back to currentVersion's own POM when maven-metadata xml is entirely missing`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(pomResponse("Wed, 03 Jan 2024 00:00:00 GMT"))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("1.0.0", result.latestVersion)
        assertEquals(Instant.parse("2024-01-03T00:00:00Z"), result.latestReleaseDate)
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // maven-metadata.xml (404)
        val fallbackRequest = server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(fallbackRequest)
        assertTrue(fallbackRequest.path?.endsWith("/1.0.0/slf4j-api-1.0.0.pom") == true)
    }

    @Test fun `still throws IOException on a genuine 5xx during the POM fetch`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

        assertFailsWith<IOException> {
            client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        }
    }

    @Test fun `retries with GET when the POM HEAD probe returns 405 Method Not Allowed`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(pomResponse("Thu, 04 Jan 2024 00:00:00 GMT"))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals(Instant.parse("2024-01-04T00:00:00Z"), result.latestReleaseDate)
        assertEquals(3, server.requestCount)
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // maven-metadata.xml
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // rejected HEAD
        val retryRequest = server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(retryRequest)
        assertEquals("GET", retryRequest.method)
        assertTrue(retryRequest.path?.endsWith("/2.0.16/slf4j-api-2.0.16.pom") == true)
    }

    @Test fun `retries with GET when the POM HEAD probe returns 501 Not Implemented`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(501))
        server.enqueue(pomResponse("Fri, 05 Jan 2024 00:00:00 GMT"))

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals(Instant.parse("2024-01-05T00:00:00Z"), result.latestReleaseDate)
        assertEquals(3, server.requestCount)
    }

    @Test fun `still throws IOException when the POM HEAD probe returns a non-retryable 403`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(MockResponse().setResponseCode(403))

        val ex =
            assertFailsWith<IOException> {
                client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
            }
        assertTrue(ex.message?.contains("403") == true)
        assertEquals(2, server.requestCount)
    }

    @Test fun `sends a HEAD request for the POM fetch, not GET`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(pomResponse())

        client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // the maven-metadata.xml GET
        val pomRequest = server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(pomRequest)
        assertEquals("HEAD", pomRequest.method)
        assertTrue(pomRequest.path?.endsWith("/2.0.16/slf4j-api-2.0.16.pom") == true)
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
        server.enqueue(pomResponse("Wed, 03 Dec 2025 08:00:00 GMT"))

        val result = client.fetchSignals("antlr", "antlr", "2.7.7")

        assertNotNull(result)
        assertEquals("2.7.7", result.latestVersion)
        assertEquals(Instant.parse("2025-12-03T08:00:00Z"), result.latestReleaseDate)
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // maven-metadata.xml
        server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) // derived-latest "2.7.6" POM
        val currentVersionRequest = server.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertNotNull(currentVersionRequest)
        assertTrue(currentVersionRequest.path?.endsWith("/2.7.7/antlr-2.7.7.pom") == true)
    }

    @Test fun `falls back to the derived latest when currentVersion's own POM is unavailable`() {
        server.enqueue(
            MockResponse().setBody(
                "<metadata><versioning><versions><version>2.7.1</version>" +
                    "<version>2.7.6</version></versions></versioning></metadata>",
            ),
        )
        server.enqueue(pomResponse("Tue, 13 Oct 2009 23:35:00 GMT"))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.fetchSignals("antlr", "antlr", "2.7.7")

        assertNotNull(result)
        assertEquals("2.7.6", result.latestVersion)
        assertEquals(Instant.parse("2009-10-13T23:35:00Z"), result.latestReleaseDate)
    }

    @Test fun `keeps the derived latest when it is already fresher than currentVersion`() {
        server.enqueue(
            MockResponse().setBody(
                "<metadata><versioning><versions><version>2.7.1</version>" +
                    "<version>2.7.6</version></versions></versioning></metadata>",
            ),
        )
        server.enqueue(pomResponse("Wed, 03 Dec 2025 08:00:00 GMT"))
        server.enqueue(pomResponse("Tue, 13 Oct 2009 23:35:00 GMT"))

        val result = client.fetchSignals("antlr", "antlr", "2.7.7")

        assertNotNull(result)
        assertEquals("2.7.6", result.latestVersion)
        assertEquals(Instant.parse("2025-12-03T08:00:00Z"), result.latestReleaseDate)
    }

    @Test fun `does not second-guess an explicit latest tag even when currentVersion is missing from versions`() {
        server.enqueue(MockResponse().setBody(metadataBody("2.0.16", "2.0.16")))
        server.enqueue(pomResponse())

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(2, server.requestCount)
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
