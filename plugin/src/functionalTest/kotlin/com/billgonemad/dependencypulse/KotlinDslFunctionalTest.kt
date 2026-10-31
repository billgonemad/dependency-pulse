package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.MockWebServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinDslFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

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

    @Test fun `plugin applies and runs from a Kotlin DSL build script`() {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test-project"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("com.billgonemad.dependency-pulse")
            }
            repositories {
                maven {
                    url = uri("http://${server.hostName}:${server.port}")
                    isAllowInsecureProtocol = true
                }
            }
            dependencies {
                compileOnly("org.slf4j:slf4j-api:2.0.16")
            }
            dependencyPulse {
                pomBaseUrl = "http://${server.hostName}:${server.port}"
                githubApiBaseUrl = "http://${server.hostName}:${server.port}"
                showGreen = true
                ignoreConfigurations = listOf("testCompileClasspath", "testRuntimeClasspath")
                thresholds {
                    yellowAfterMonths = 12
                }
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withCompatGradleVersion()
                .withArguments("dependencyPulse")
                .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dependencyPulse")?.outcome)
        assertTrue(result.output.contains("Dependency Pulse Report"))
        assertTrue(result.output.contains("slf4j-api"))
        assertTrue(result.output.contains("1 dependency scanned."), result.output)
    }
}
