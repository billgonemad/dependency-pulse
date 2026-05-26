package com.billgonemad.dependencypulse

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DependencyPulsePluginTest {
    @Test fun `plugin registers dependencyPulse task`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        assertNotNull(project.tasks.findByName("dependencyPulse"))
    }

    @Test fun `extension is created with correct defaults`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val ext = project.extensions.getByType(DependencyPulseExtension::class.java)

        assertEquals(false, ext.failOnRed)
        assertEquals(false, ext.failOnError)
        assertEquals(12, ext.thresholds.yellowAfterMonths)
        assertEquals(24, ext.thresholds.redAfterMonths)
        assertNotNull(ext.ignoreConfigurations)
    }

    @Test fun `afterEvaluate propagates extension values to task`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val ext = project.extensions.getByType(DependencyPulseExtension::class.java)
        ext.failOnRed = true
        ext.failOnError = true
        ext.ignoreConfigurations = listOf("compileClasspath")
        ext.thresholds { t ->
            t.yellowAfterMonths = 6
            t.redAfterMonths = 18
        }

        (project as ProjectInternal).evaluate()

        val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
        assertEquals(true, task.failOnRed.get())
        assertEquals(true, task.failOnError.get())
        assertEquals(listOf("compileClasspath"), task.ignoreConfigurations.get())
        assertEquals(6, task.yellowAfterMonths.get())
        assertEquals(18, task.redAfterMonths.get())
        assertEquals("https://search.maven.org", task.mavenCentralBaseUrl.get())
    }

    @Test fun `extension githubToken defaults to null`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val ext = project.extensions.getByType(DependencyPulseExtension::class.java)
        assertEquals(null, ext.githubToken)

        ext.githubToken = "token-value"
        assertEquals("token-value", ext.githubToken)
    }
}
