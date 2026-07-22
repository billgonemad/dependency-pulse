package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyPulsePluginFunctionalTest {
    companion object {
        private const val THREE_YEARS_MS = 3L * 365 * 24 * 3600 * 1000
        private const val HTTP_503 = 503
        private const val HTTP_404 = 404
        private const val TAKE_REQUEST_TIMEOUT_SECONDS = 5L

        // Minimal valid empty ZIP (End Of Central Directory record, zero entries) — enough for
        // Gradle's lenient artifact resolution to accept a .jar response; fixtures declare no
        // .java source, so nothing ever compiles against the jar's actual bytecode contents.
        private val EMPTY_ZIP_BYTES =
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }

    @field:TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle") }

    private lateinit var server: MockWebServer

    private fun mavenDispatcher(
        latestVersion: String,
        lastModifiedEpochMs: Long,
        scmUrl: String? = null,
        // false for a "healthy" repo that exists solely so Gradle's own dependency resolution
        // doesn't drop the coordinate (see the two failOn* tests below) but must NOT itself supply
        // real Maven signals to the plugin's walk loop — Gradle never needs maven-metadata.xml to
        // resolve a fixed (non-range, non-SNAPSHOT) version, so 404-ing it here doesn't affect
        // Gradle's resolution, only the plugin's own fetchSignals call against this repo.
        serveMetadata: Boolean = true,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("maven-metadata.xml") && serveMetadata ->
                        MockResponse().setBody(
                            "<metadata><versioning><latest>$latestVersion</latest>" +
                                "<versions><version>$latestVersion</version></versions></versioning></metadata>",
                        )
                    path.endsWith(".jar") -> MockResponse().setBody(Buffer().write(EMPTY_ZIP_BYTES))
                    path.endsWith(".pom") -> {
                        val httpDate =
                            DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                Instant.ofEpochMilli(lastModifiedEpochMs).atZone(ZoneOffset.UTC),
                            )
                        val scmFragment = scmUrl?.let { "<scm><url>$it</url></scm>" }.orEmpty()
                        // Gradle's real POM resolver requires groupId/artifactId/version to be
                        // present AND to match the requested coordinate exactly (verified: a
                        // mismatch fails with "inconsistent module metadata found", not just a
                        // parse error) — derived here from the standard Maven layout path
                        // (/{group-with-slashes}/{artifactId}/{version}/{artifactId}-{version}.pom)
                        // rather than hardcoded, so this dispatcher works for any coordinate a
                        // test declares without adding parameters.
                        val segments = path.removePrefix("/").split("/")
                        val version = segments[segments.size - 2]
                        val artifactId = segments[segments.size - 3]
                        val groupId = segments.subList(0, segments.size - 3).joinToString(".")
                        MockResponse()
                            .setBody(
                                "<project><groupId>$groupId</groupId><artifactId>$artifactId</artifactId>" +
                                    "<version>$version</version>$scmFragment</project>",
                            ).setHeader("Last-Modified", httpDate)
                    }
                    // Gradle probes for .module (Gradle Module Metadata) and .sha1/.md5 checksum
                    // files before falling back to the .pom alone; a clean 404 here is what makes
                    // it fall back cleanly. Serving 200+XML for these (as an earlier version of
                    // this dispatcher did) makes Gradle try to parse the response as real module
                    // metadata, fail, and silently drop the dependency via lenient resolution —
                    // this was verified in a spike; a real repository behaves the same way.
                    else -> MockResponse().setResponseCode(HTTP_404)
                }
            }
        }

    private fun githubDispatcher(pushedAt: String): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("/commits?per_page=1") == true) {
                    MockResponse().setBody("""[{"commit":{"committer":{"date":"$pushedAt"}}}]""")
                } else {
                    MockResponse().setBody("""{"archived":false,"pushed_at":"$pushedAt"}""")
                }
        }

    // Every fixture's repositories {} block points here — real jar resolution and the plugin's
    // own metadata queries hit the same mock server, so no fixture ever touches the real internet.
    private fun MockWebServer.repositoriesBlock(): String =
        """
        repositories {
            maven {
                url = uri("http://$hostName:$port")
                allowInsecureProtocol = true
            }
        }
        """.trimIndent()

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
                        "-DgithubApiBaseUrl=http://${githubServer.hostName}:${githubServer.port}",
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

    @Test fun `a dependency only resolvable via a second declared repo is reported using that repo's data`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(HTTP_404)
            }

        MockWebServer().apply { dispatcher = mavenDispatcher("9.9.9", System.currentTimeMillis()) }.use { secondServer ->
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
            assertTrue(result.output.contains("9.9.9"), "expected the second repo's version to win:\n${result.output}")
            assertTrue(result.output.contains("1 green"))
        }
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
            ${server.repositoriesBlock()}
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
            ${server.repositoriesBlock()}
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
            ${server.repositoriesBlock()}
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
            ${server.repositoriesBlock()}
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
}

private fun GradleRunner.withCompatGradleVersion(): GradleRunner {
    val version = System.getProperty("testGradleVersion")?.takeIf { it.isNotBlank() }
    return if (version != null) withGradleVersion(version) else this
}
