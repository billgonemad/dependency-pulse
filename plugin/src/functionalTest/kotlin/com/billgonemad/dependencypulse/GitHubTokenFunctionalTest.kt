package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockWebServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitHubTokenFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle") }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun githubAuthorizationHeader(
        envToken: String,
        extensionToken: String? = null,
    ): String? {
        server.dispatcher =
            mavenDispatcher(
                "2.0.16",
                System.currentTimeMillis(),
                scmUrl = "https://github.com/example-owner/example-repo",
            )

        return MockWebServer().apply { dispatcher = githubDispatcher(Instant.now().toString()) }.use { githubServer ->
            githubServer.start()

            settingsFile.writeText("rootProject.name = 'test-project'")
            buildFile.writeText(
                """
                plugins {
                    id 'java-library'
                    id 'com.billgonemad.dependency-pulse'
                }
                ${server.repositoriesBlock()}
                dependencies {
                    compileOnly 'org.slf4j:slf4j-api:2.0.16'
                }
                dependencyPulse {
                    pomBaseUrl = "http://${server.hostName}:${server.port}"
                    githubApiBaseUrl = "http://${githubServer.hostName}:${githubServer.port}"
                    ${extensionToken?.let { "githubToken = '$it'" }.orEmpty()}
                }
                """.trimIndent(),
            )

            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withEnvironment(System.getenv() + ("GITHUB_TOKEN" to envToken))
                .withArguments("dependencyPulse")
                .build()

            val githubRequest = githubServer.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(githubRequest, "expected a request to the GitHub mock server, but none arrived")
            githubRequest.getHeader("Authorization")
        }
    }

    @Test fun `githubToken defaults to the GITHUB_TOKEN environment variable`() {
        assertEquals("Bearer env-token", githubAuthorizationHeader(envToken = "env-token"))
    }

    @Test fun `an explicit githubToken takes precedence over the GITHUB_TOKEN environment variable`() {
        assertEquals(
            "Bearer extension-token",
            githubAuthorizationHeader(envToken = "env-token", extensionToken = "extension-token"),
        )
    }

    @Test fun `a blank GITHUB_TOKEN environment variable sends no Authorization header`() {
        assertNull(githubAuthorizationHeader(envToken = ""))
    }
}
