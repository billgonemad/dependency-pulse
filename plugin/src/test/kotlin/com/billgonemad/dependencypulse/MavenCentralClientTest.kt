package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"numFound":1,"docs":[{"timestamp":1700000000000}]}}""",
            ),
        )

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "2.0.16")

        assertNotNull(result)
        assertEquals("2.0.16", result.latestVersion)
        assertNotNull(result.latestReleaseDate)
        assertNotNull(result.currentVersionDate)
    }

    @Test fun `returns null when artifact not found on Central`() {
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"numFound":0,"docs":[]}}""",
            ),
        )

        val result = client.fetchSignals("com.example", "nonexistent", "1.0")

        assertNull(result)
    }

    @Test fun `currentVersionDate is null when specific version not found`() {
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"numFound":1,"docs":[{"latestVersion":"2.0.16","timestamp":1722729600000}]}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"response":{"numFound":0,"docs":[]}}""",
            ),
        )

        val result = client.fetchSignals("org.slf4j", "slf4j-api", "1.0.0-SNAPSHOT")

        assertNotNull(result)
        assertNull(result.currentVersionDate)
    }

    @Test fun `throws when server is unreachable`() {
        server.shutdown()

        assertFailsWith<Exception> {
            client.fetchSignals("org.slf4j", "slf4j-api", "2.0.16")
        }
    }
}
