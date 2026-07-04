package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenCentralClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MavenCentralClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            MavenCentralClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
                retryDelayMs = 0L,
            )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test fun `returns MavenSignals when artifact found`() {
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"docs":[{"v":"2.0.16","timestamp":1722729600000}]}}""",
            ),
        )

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertNotNull(result.latestReleaseDate)
    }

    @Test fun `returns null when artifact not found on Central`() {
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"docs":[]}}""",
            ),
        )

        val result = client.fetchSignals("com.example", "nonexistent", "1.0.0")

        assertNull(result)
    }

    @Test fun `throws when server is unreachable`() {
        server.shutdown()

        assertFailsWith<Exception> {
            client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        }
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
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"docs":[{"v":"2.0.16","timestamp":1722729600000}]}}""",
            ),
        )

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(2, server.requestCount)
    }

    @Test fun `retries on 503 and returns result on subsequent 200`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"docs":[{"v":"2.0.16","timestamp":1722729600000}]}}""",
            ),
        )

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertEquals(2, server.requestCount)
    }

    @Test fun `caches URL responses — server receives each URL only once per client instance`() {
        // fetchSignals makes 1 request per artifact.
        // Calling it twice with identical args should hit the cache on the second call.
        // We enqueue 2 responses but expect only 1 to be consumed.
        repeat(2) {
            server.enqueue(
                MockResponse().setBody(
                    """{"response":{"docs":[{"v":"2.0.16","timestamp":1722729600000}]}}""",
                ),
            )
        }

        client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0")
        client.fetchSignals("org.slf4j", "slf4j-api", "2.0.0")

        assertEquals(1, server.requestCount)
    }

    @Test fun `selects latest stable from a mixed version list`() {
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"docs":[
                    {"v":"5.0.0-alpha.16","timestamp":1748476800000},
                    {"v":"4.12.0","timestamp":1697500800000},
                    {"v":"4.11.0","timestamp":1682208000000}
                ]}}""",
            ),
        )

        val result = client.fetchSignals("com.squareup.okhttp3", "okhttp", "4.12.0")

        assertNotNull(result)
        assertEquals("4.12.0", result.latestVersion)
    }

    @Test fun `tolerates central sonatype com's response shape (ec collapsed, tags field absent)`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "responseHeader": {"params": {"sort": ""}},
                    "response": {
                        "numFound": 290,
                        "docs": [
                            {
                                "id": "org.springframework.boot:spring-boot-cli:4.1.0",
                                "g": "org.springframework.boot",
                                "a": "spring-boot-cli",
                                "v": "4.1.0",
                                "p": "jar",
                                "timestamp": 1749556800000,
                                "ec": ["jar"]
                            }
                        ]
                    }
                }""",
            ),
        )

        val result = client.fetchSignals("org.springframework.boot", "spring-boot-cli", "3.5.15")

        assertNotNull(result)
        assertEquals("4.1.0", result.latestVersion)
    }
}
