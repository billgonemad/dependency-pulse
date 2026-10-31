package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection.HTTP_UNAVAILABLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportLoggingFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

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

    private fun writeBuild(
        repoServer: MockWebServer,
        extraConfig: String = "",
    ) {
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'test-project'")
        projectDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            ${repoServer.repositoriesBlock()}
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
                $extraConfig
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withCompatGradleVersion()
            .withArguments(*args)

    @Test fun `report is still shown under --quiet`() {
        server.dispatcher = mavenDispatcher("2.0.16", System.currentTimeMillis())
        writeBuild(server)

        val result = runner("dependencyPulse", "--quiet").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(result.output.contains("Dependency Pulse Report"), result.output)
        assertTrue(result.output.contains("1 dependency scanned."), result.output)
    }

    @Test fun `failOnRed failure under --quiet names the RED dependency`() {
        val threeYearsAgo = System.currentTimeMillis() - THREE_YEARS_MS
        server.dispatcher = mavenDispatcher("0.1", threeYearsAgo)
        writeBuild(server, "failOnRed = true")

        val result = runner("dependencyPulse", "--quiet").buildAndFail()

        assertTrue(
            result.output.contains("RED dependencies detected: org.slf4j:slf4j-api:2.0.16"),
            result.output,
        )
    }

    @Test fun `failOnError failure under --quiet names the unchecked dependency`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(HTTP_UNAVAILABLE)
            }
        // Gradle's own resolution needs a healthy repo, or the dependency never reaches the
        // analyzer; serveMetadata=false keeps that repo from supplying the walk with fresh data.
        MockWebServer()
            .apply { dispatcher = mavenDispatcher("2.0.16", System.currentTimeMillis(), serveMetadata = false) }
            .use { repoServer ->
                repoServer.start()
                writeBuild(repoServer, "failOnError = true")

                val result = runner("-DmavenCentralRetryDelayMs=0", "dependencyPulse", "--quiet").buildAndFail()

                assertTrue(
                    result.output.contains("could not be checked: org.slf4j:slf4j-api:2.0.16"),
                    result.output,
                )
            }
    }
}
