package com.billgonemad.dependencypulse

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DependencyPulsePluginFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle") }

    @Test fun `dependencyPulse task can be registered and run`() {
        settingsFile.writeText("rootProject.name = 'test-project'")
        buildFile.writeText(
            """
            plugins {
                id('com.billgonemad.dependency-pulse')
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("dependencyPulse")
                .build()

        assertTrue(result.output.isNotEmpty())
    }
}
