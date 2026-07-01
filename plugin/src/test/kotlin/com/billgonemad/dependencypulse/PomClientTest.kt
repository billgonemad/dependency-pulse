package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PomClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PomClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            PomClient(
                baseUrl = "http://${server.hostName}:${server.port}",
                httpClient = HttpClient.newHttpClient(),
            )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test fun `normalizes a plain https github url`() {
        assertEquals("owner/repo", normalizeGitHubUrl("https://github.com/owner/repo"))
    }

    @Test fun `normalizes an https github url with dot-git suffix`() {
        assertEquals("owner/repo", normalizeGitHubUrl("https://github.com/owner/repo.git"))
    }

    @Test fun `normalizes a scm git plus ssh form`() {
        assertEquals("owner/repo", normalizeGitHubUrl("scm:git:git@github.com:owner/repo.git"))
    }

    @Test fun `normalizes a scm git plus https form`() {
        assertEquals("owner/repo", normalizeGitHubUrl("scm:git:https://github.com/owner/repo.git"))
    }

    @Test fun `normalizes an https url with a trailing slash`() {
        assertEquals("owner/repo", normalizeGitHubUrl("https://github.com/owner/repo/"))
    }

    @Test fun `returns null for a non-github host`() {
        assertNull(normalizeGitHubUrl("https://gitlab.com/owner/repo"))
    }

    @Test fun `returns null for a host that merely contains github-dot-com as a substring`() {
        assertNull(normalizeGitHubUrl("https://not-github.com/owner/repo"))
    }

    @Test fun `returns null for a null input`() {
        assertNull(normalizeGitHubUrl(null))
    }

    @Test fun `returns null for a blank string`() {
        assertNull(normalizeGitHubUrl(""))
    }

    @Test fun `requests the pom at the maven coordinate path`() {
        server.enqueue(MockResponse().setResponseCode(404))

        client.fetchGitHubRepo("org.slf4j", "slf4j-api", "2.0.16")

        val recorded = server.takeRequest()
        assertEquals("/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.pom", recorded.path)
    }

    @Test fun `prefers scm url over connection and developerConnection`() {
        server.enqueue(
            MockResponse().setBody(
                """
                <project>
                  <scm>
                    <url>https://github.com/owner/from-url</url>
                    <connection>scm:git:git@github.com:owner/from-connection.git</connection>
                    <developerConnection>scm:git:git@github.com:owner/from-dev.git</developerConnection>
                  </scm>
                </project>
                """.trimIndent(),
            ),
        )

        assertEquals("owner/from-url", client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `falls back to scm connection when url is absent`() {
        server.enqueue(
            MockResponse().setBody(
                """
                <project>
                  <scm>
                    <connection>scm:git:git@github.com:owner/repo.git</connection>
                  </scm>
                </project>
                """.trimIndent(),
            ),
        )

        assertEquals("owner/repo", client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `falls back to scm developerConnection when url and connection are absent`() {
        server.enqueue(
            MockResponse().setBody(
                """
                <project>
                  <scm>
                    <developerConnection>scm:git:https://github.com/owner/repo.git</developerConnection>
                  </scm>
                </project>
                """.trimIndent(),
            ),
        )

        assertEquals("owner/repo", client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `falls back to project url when scm is absent`() {
        server.enqueue(
            MockResponse().setBody(
                """
                <project>
                  <url>https://github.com/owner/repo</url>
                </project>
                """.trimIndent(),
            ),
        )

        assertEquals("owner/repo", client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `returns null when scm and url are both non-github`() {
        server.enqueue(
            MockResponse().setBody(
                """
                <project>
                  <scm>
                    <url>https://gitlab.com/owner/repo</url>
                  </scm>
                  <url>https://example.com/project</url>
                </project>
                """.trimIndent(),
            ),
        )

        assertNull(client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `returns null when pom is not found`() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `returns null when the server is unreachable`() {
        server.shutdown()

        assertNull(client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `returns null when a coordinate produces an invalid uri`() {
        assertNull(client.fetchGitHubRepo("g", "artifact with spaces", "1.0"))
    }

    @Test fun `returns null for malformed xml`() {
        server.enqueue(MockResponse().setBody("not valid xml <<<"))

        assertNull(client.fetchGitHubRepo("g", "a", "1.0"))
    }

    @Test fun `returns null when project has neither scm nor url`() {
        server.enqueue(MockResponse().setBody("<project></project>"))

        assertNull(client.fetchGitHubRepo("g", "a", "1.0"))
    }
}
