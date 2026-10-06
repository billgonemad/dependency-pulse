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
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyPulsePluginFunctionalTest {
    companion object {
        private const val THREE_YEARS_MS = 3L * 365 * 24 * 3600 * 1000
        private const val HTTP_503 = 503
    }

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

    @Test fun `dependencyPulse task reports dependencies`() {
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
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
                    "dependencyPulse",
                    "--show-green",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(result.output.contains("Dependency Pulse Report"))
        assertTrue(result.output.contains("slf4j-api"))
        assertTrue(result.output.contains("dependencies scanned"))
    }

    @Test fun `Maven and GitHub requests are routed to their own configured base URLs`() {
        val pushedAt = Instant.now().toString()
        server.dispatcher =
            mavenDispatcher(
                "2.0.16",
                System.currentTimeMillis(),
                scmUrl = "https://github.com/example-owner/example-repo",
            )

        MockWebServer().apply { dispatcher = githubDispatcher(pushedAt) }.use { githubServer ->
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
                        "dependencyPulse",
                        "--show-green",
                    ).build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
            assertTrue(result.output.contains("1 green"), "expected the Maven-side fetch to succeed (1 green)")
            val githubRequest = githubServer.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(githubRequest, "expected a request to the GitHub mock server, but none arrived")
            assertTrue(githubRequest.path?.startsWith("/repos/example-owner/example-repo") == true)
        }
    }

    @Test fun `a github repo link only present on a second declared repo's POM is used to resolve GitHub signals`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(HTTP_404)
            }

        val pushedAt = Instant.now().toString()
        val secondDispatcher =
            mavenDispatcher(
                "2.0.16",
                System.currentTimeMillis(),
                scmUrl = "https://github.com/example-owner/example-repo",
            )
        MockWebServer().apply { dispatcher = secondDispatcher }.use { secondServer ->
            secondServer.start()

            MockWebServer().apply { dispatcher = githubDispatcher(pushedAt) }.use { githubServer ->
                githubServer.start()

                settingsFile.writeText("rootProject.name = 'test-project'")
                buildFile.writeText(
                    """
                    plugins {
                        id 'java-library'
                        id 'com.billgonemad.dependency-pulse'
                    }
                    repositories {
                        maven {
                            url = uri("http://${server.hostName}:${server.port}")
                            allowInsecureProtocol = true
                        }
                        maven {
                            url = uri("http://${secondServer.hostName}:${secondServer.port}")
                            allowInsecureProtocol = true
                        }
                    }
                    dependencies {
                        compileOnly 'org.slf4j:slf4j-api:2.0.16'
                    }
                    dependencyPulse {
                        pomBaseUrl = "http://${server.hostName}:${server.port}"
                        githubApiBaseUrl = "http://${githubServer.hostName}:${githubServer.port}"
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
                            "dependencyPulse",
                            "--show-green",
                        ).build()

                assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
                val githubRequest = githubServer.takeRequest(TAKE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertNotNull(githubRequest, "expected a request to the GitHub mock server, but none arrived")
                assertTrue(githubRequest.path?.startsWith("/repos/example-owner/example-repo") == true)
            }
        }
    }

    @Test fun `a dependency only resolvable via a second declared repo is reported using that repo's data`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(HTTP_404)
            }

        val secondDispatcher = mavenDispatcher("9.9.9", System.currentTimeMillis())
        MockWebServer().apply { dispatcher = secondDispatcher }.use { secondServer ->
            secondServer.start()

            settingsFile.writeText("rootProject.name = 'test-project'")
            buildFile.writeText(
                """
                plugins {
                    id 'java-library'
                    id 'com.billgonemad.dependency-pulse'
                }
                repositories {
                    maven {
                        url = uri("http://${server.hostName}:${server.port}")
                        allowInsecureProtocol = true
                    }
                    maven {
                        url = uri("http://${secondServer.hostName}:${secondServer.port}")
                        allowInsecureProtocol = true
                    }
                }
                dependencies {
                    compileOnly 'org.slf4j:slf4j-api:2.0.16'
                }
                dependencyPulse {
                    pomBaseUrl = "http://${server.hostName}:${server.port}"
                    githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
                        "dependencyPulse",
                        "--show-green",
                    ).build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
            assertTrue(result.output.contains("9.9.9"), "expected the second repo's version to win:\n${result.output}")
            assertTrue(result.output.contains("1 green"))
        }
    }

    @Test
    fun `a dependency whose selected latest POM 404s falls back to its own current-version POM instead of UNKNOWN`() {
        server.dispatcher =
            mavenDispatcher(
                latestVersion = "9.9.9",
                lastModifiedEpochMs = System.currentTimeMillis(),
                pomNotFoundForVersion = "9.9.9",
            )

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
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
                    "dependencyPulse",
                    "--show-green",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertFalse(
            result.output.contains("❓"),
            "expected a real status, not UNKNOWN:\n${result.output}",
        )
        assertFalse(
            result.output.contains("Maven Central unavailable"),
            "expected no UNKNOWN message:\n${result.output}",
        )
        assertTrue(
            result.output.contains("Latest: unknown (repository metadata incomplete)"),
            "expected the honest unverified fallback label, not a fabricated version:\n${result.output}",
        )
        assertTrue(result.output.contains("1 green"))
    }

    @Test fun `default output hides GREEN dependencies that --show-green would reveal`() {
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
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
            ${server.repositoriesBlock()}
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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

        // server 503s every path, including .jar/.pom — fine for simulating "the plugin's own
        // pomBaseUrl-directed metadata fetch fails", but repositories {} needs real, resolvable
        // artifacts for Gradle's own dependency resolution or the dependency never reaches
        // analyzeOne at all (lenient resolution just drops it, and there'd be nothing to fail
        // on). A second, healthy mock server backs repositories {} instead; pomBaseUrl still
        // points at the broken one. serveMetadata=false keeps this repo out of the walk loop's
        // own data (it 404s maven-metadata.xml) while still serving .jar/.pom so Gradle's real
        // dependency resolution succeeds — otherwise the walk loop would find real, fresh data
        // here and report GREEN instead of the UNKNOWN this test expects.
        val repoDispatcher = mavenDispatcher("2.0.16", System.currentTimeMillis(), serveMetadata = false)
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
                    compileOnly 'org.slf4j:slf4j-api:2.0.16'
                }
                dependencyPulse {
                    failOnError = true
                    pomBaseUrl = "http://${server.hostName}:${server.port}"
                    githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
                        "-DmavenCentralRetryDelayMs=0",
                        "dependencyPulse",
                    ).buildAndFail()

            assertTrue(result.output.contains("❓"))
        }
    }

    @Test fun `runOnCheck=true wires dependencyPulse into the check lifecycle task`() {
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
                runOnCheck = true
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
            ${server.repositoriesBlock()}
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                runOnCheck = false
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
                    "check",
                ).build()

        assertEquals(null, result.task(":dependencyPulse")?.outcome)
    }

    // Absence from the task graph is a proxy for "the coordinate/repo-URL providers were never
    // realized," not a direct observation of it: tasks.register laziness means the task's
    // configuration block (and therefore the provider wiring) only runs if the task enters the
    // graph, so a null outcome here implies the providers were never realized either.
    @Test fun `runOnCheck=false does not wire dependencyPulse under --configuration-cache either`() {
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
                runOnCheck = false
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
                    "check",
                    "--configuration-cache",
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
            ${server.repositoriesBlock()}
            dependencies {
                compileOnly 'org.slf4j:slf4j-api:2.0.16'
            }
            dependencyPulse {
                failOnRed = true
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
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
            ${server.repositoriesBlock()}
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

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
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

        // See the comment in `failOnError causes build failure when Maven Central returns an
        // error` above: server 404s every path, so repositories {} needs a separate, healthy
        // mock server or Gradle's own dependency resolution drops the coordinate before the
        // plugin ever sees it. serveMetadata=false keeps this repo out of the walk loop's own
        // data for the same reason as that test.
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

            val result =
                GradleRunner
                    .create()
                    .withProjectDir(projectDir)
                    .withPluginClasspath()
                    .withCompatGradleVersion()
                    .withArguments(
                        "dependencyPulse",
                    ).buildAndFail()

            assertTrue(result.output.contains("🔴"))
        }
    }

    @Test fun `dependencyPulse runs under --configuration-cache and reuses the cache on a second run`() {
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
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
            }
            """.trimIndent(),
        )

        val firstRun =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "dependencyPulse",
                    "--configuration-cache",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, firstRun.task(":dependencyPulse")?.outcome)
        assertTrue(firstRun.output.contains("Configuration cache entry stored"))

        val secondRun =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments(
                    "dependencyPulse",
                    "--configuration-cache",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, secondRun.task(":dependencyPulse")?.outcome)
        assertTrue(secondRun.output.contains("Configuration cache entry reused"))
    }
}
