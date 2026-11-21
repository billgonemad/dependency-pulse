package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotPublishedFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle") }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = mavenDispatcher("2.0.16", System.currentTimeMillis())
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test fun `failOnRed does not fail when a knownStableGroups match is not found in any walked repo`() {
        server.dispatcher = notFoundDispatcher()

        val repoDispatcher = mavenDispatcher("3.0.0", System.currentTimeMillis(), serveMetadata = false)
        MockWebServer().apply { dispatcher = repoDispatcher }.use { repoServer ->
            repoServer.start()

            settingsFile.writeText("rootProject.name = 'test-project'")
            buildFile.writeText(
                """
                plugins {
                    id 'java-library'
                    id 'com.billgonemad.dependency-pulse'
                }
                ${repoServer.repositoriesBlock()}
                dependencies {
                    compileOnly 'jakarta.annotation:jakarta.annotation-api:3.0.0'
                }
                dependencyPulse {
                    failOnRed = true
                    pomBaseUrl = "http://${server.hostName}:${server.port}"
                    githubApiBaseUrl = "http://${server.hostName}:${server.port}"
                }
                """.trimIndent(),
            )

            val result = projectDir.runner("dependencyPulse").build()

            assertTrue(result.output.contains("❓"), result.output)
            assertTrue(result.output.contains("1 unknown, 0 stable"), result.output)
            assertFalse(result.output.contains("🔴"), result.output)
            assertFalse(result.output.contains("no longer published"), result.output)
            assertTrue(result.output.contains("Not found in any repository dependencyPulse checked"), result.output)
        }
    }

    @Test fun `failOnRed still fails when a knownStableGroups match is not found and the GitHub repo is archived`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path?.startsWith("/repos/") == true) {
                        githubDispatcher("2020-01-01T00:00:00Z", archived = true).dispatch(request)
                    } else {
                        MockResponse().setResponseCode(HTTP_404)
                    }
            }

        val repoDispatcher =
            mavenDispatcher(
                "3.0.0",
                System.currentTimeMillis(),
                scmUrl = "https://github.com/example-owner/example-repo",
                serveMetadata = false,
            )
        MockWebServer().apply { dispatcher = repoDispatcher }.use { repoServer ->
            repoServer.start()

            settingsFile.writeText("rootProject.name = 'test-project'")
            buildFile.writeText(
                """
                plugins {
                    id 'java-library'
                    id 'com.billgonemad.dependency-pulse'
                }
                ${repoServer.repositoriesBlock()}
                dependencies {
                    compileOnly 'jakarta.annotation:jakarta.annotation-api:3.0.0'
                }
                dependencyPulse {
                    failOnRed = true
                    pomBaseUrl = "http://${server.hostName}:${server.port}"
                    githubApiBaseUrl = "http://${server.hostName}:${server.port}"
                }
                """.trimIndent(),
            )

            val result = projectDir.runner("dependencyPulse").buildAndFail()

            assertTrue(result.output.contains("🔴"), result.output)
            assertFalse(result.output.contains("no longer published"), result.output)
        }
    }

    @Test fun `dependency declared only in settings repositories is UNKNOWN not RED`() {
        withSettingsOnlyRepo(failOnRed = true) {
            val result = projectDir.runner("dependencyPulse").build()

            assertTrue(result.output.contains("❓"), result.output)
            assertTrue(result.output.contains("1 unknown"), result.output)
            assertFalse(result.output.contains("🔴"), result.output)
            assertTrue(result.output.contains("Not found in any repository dependencyPulse checked"), result.output)
        }
    }

    @Test fun `notPublished dependency fails build only with failOnError`() {
        withSettingsOnlyRepo(failOnError = true) {
            val result = projectDir.runner("dependencyPulse").buildAndFail()

            assertTrue(result.output.contains("could not be checked: org.slf4j:slf4j-api:2.0.16"), result.output)
            assertTrue(result.output.contains("❓"), result.output)
            assertTrue(result.output.contains("Not found in any repository dependencyPulse checked"), result.output)
        }
    }

    private fun withSettingsOnlyRepo(
        failOnRed: Boolean = false,
        failOnError: Boolean = false,
        block: () -> Unit,
    ) {
        server.dispatcher = notFoundDispatcher()
        val repoDispatcher = mavenDispatcher("2.0.16", System.currentTimeMillis(), serveMetadata = false)
        MockWebServer().apply { dispatcher = repoDispatcher }.use { repoServer ->
            repoServer.start()

            settingsFile.writeText(
                """
                rootProject.name = 'test-project'
                ${repoServer.settingsRepositoriesBlock()}
                """.trimIndent(),
            )
            buildFile.writeText(
                """
                plugins {
                    id 'java-library'
                    id 'com.billgonemad.dependency-pulse'
                }
                dependencies {
                    compileOnly 'org.slf4j:slf4j-api:2.0.16'
                }
                dependencyPulse {
                    failOnRed = $failOnRed
                    failOnError = $failOnError
                    pomBaseUrl = "http://${server.hostName}:${server.port}"
                    githubApiBaseUrl = "http://${server.hostName}:${server.port}"
                }
                """.trimIndent(),
            )
            block()
        }
    }
}
