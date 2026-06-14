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
                """{"response":{"numFound":1,"docs":[{"latestVersion":"2.0.16","timestamp":1722729600000}]}}""",
            ),
        )

        val result = client.fetchSignals("org.slf4j", "slf4j-api")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertNotNull(result.latestReleaseDate)
    }

    @Test fun `returns null when artifact not found on Central`() {
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"numFound":0,"docs":[]}}""",
            ),
        )

        val result = client.fetchSignals("com.example", "nonexistent")

        assertNull(result)
    }

    @Test fun `throws when server is unreachable`() {
        server.shutdown()

        assertFailsWith<Exception> {
            client.fetchSignals("org.slf4j", "slf4j-api")
        }
    }

    @Test fun `throws IOException on non-200 response`() {
        server.enqueue(MockResponse().setResponseCode(429))

        val ex =
            assertFailsWith<IOException> {
                client.fetchSignals("org.slf4j", "slf4j-api")
            }
        assertTrue(ex.message?.contains("429") == true)
    }
}
