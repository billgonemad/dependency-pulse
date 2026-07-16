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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyPulsePluginFunctionalTest {
    companion object {
        private const val THREE_YEARS_MS = 3L * 365 * 24 * 3600 * 1000
        private const val HTTP_503 = 503
        private const val HTTP_404 = 404
    }

    @field:TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle") }

    private lateinit var server: MockWebServer

    private fun mavenDispatcher(
        latestVersion: String,
        lastModifiedEpochMs: Long,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("maven-metadata.xml") == true) {
                    MockResponse().setBody(
                        "<metadata><versioning><latest>$latestVersion</latest>" +
                            "<versions><version>$latestVersion</version></versions></versioning></metadata>",
                    )
                } else {
                    val httpDate =
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            Instant.ofEpochMilli(lastModifiedEpochMs).atZone(ZoneOffset.UTC),
                        )
                    MockResponse().setBody("<project></project>").setHeader("Last-Modified", httpDate)
                }
        }

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

    @Test fun `dependencyPulse task reports dependencies`() {
        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "dependencyPulse",
                    "--show-green",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(result.output.contains("Dependency Pulse Report"))
        assertTrue(result.output.contains("slf4j-api"))
        assertTrue(result.output.contains("dependencies scanned"))
    }

    @Test fun `default output hides GREEN dependencies that --show-green would reveal`() {
        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "dependencyPulse",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(!result.output.contains("slf4j-api"))
        assertTrue(result.output.contains("1 green"))
    }

    @Test fun `--summary-only suppresses per-dependency lines`() {
        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "dependencyPulse",
                    "--summary-only",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(!result.output.contains("slf4j-api"))
        assertTrue(result.output.contains("dependencies scanned"))
    }

    @Test fun `failOnError causes build failure when Maven Central returns an error`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(HTTP_503)
            }

        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                failOnError = true
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "-DmavenCentralRetryDelayMs=0",
                    "dependencyPulse",
                ).buildAndFail()

        assertTrue(result.output.contains("❓"))
    }

    @Test fun `runOnCheck=true wires dependencyPulse into the check lifecycle task`() {
        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                runOnCheck = true
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "check",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
    }

    @Test fun `runOnCheck=false does not wire dependencyPulse into the check lifecycle task`() {
        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                runOnCheck = false
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "check",
                ).build()

        assertEquals(null, result.task(":dependencyPulse")?.outcome)
    }

    @Test fun `failOnRed causes build failure when latest release is stale`() {
        val threeYearsAgo = System.currentTimeMillis() - THREE_YEARS_MS
        server.dispatcher = mavenDispatcher("0.1", threeYearsAgo)

        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                failOnRed = true
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "dependencyPulse",
                ).buildAndFail()

        assertTrue(result.output.contains("🔴"))
    }

    @Test fun `failOnRed does not fail when the only RED dependency matches knownStableGroups`() {
        val threeYearsAgo = System.currentTimeMillis() - THREE_YEARS_MS
        server.dispatcher = mavenDispatcher("3.0.0", threeYearsAgo)

        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'jakarta.annotation:jakarta.annotation-api:3.0.0'
            }
            dependencyPulse {
                failOnRed = true
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "dependencyPulse",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(result.output.contains("📘"))
        assertTrue(result.output.contains("Spec (stable)"))
    }

    @Test fun `failOnRed still fails when a knownStableGroups match has no Maven data`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(HTTP_404)
            }

        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id 'java-library'
                id 'com.billgonemad.dependency-pulse'
            }
            repositories { mavenCentral() }
            dependencies {
                compileOnly 'jakarta.annotation:jakarta.annotation-api:3.0.0'
            }
            dependencyPulse {
                failOnRed = true
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "-DpomBaseUrl=http://${server.hostName}:${server.port}",
                    "-DgithubApiBaseUrl=http://${server.hostName}:${server.port}",
                    "dependencyPulse",
                ).buildAndFail()

        assertTrue(result.output.contains("🔴"))
    }
}

private fun GradleRunner.withCompatGradleVersion(): GradleRunner {
    val version = System.getProperty("testGradleVersion")?.takeIf { it.isNotBlank() }
    return if (version != null) withGradleVersion(version) else this
}
