package com.billgonemad.dependencypulse

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

        assertEquals(false, ext.failOnRed.get())
        assertEquals(false, ext.failOnError.get())
        assertEquals(12, ext.thresholds.yellowAfterMonths.get())
        assertEquals(24, ext.thresholds.redAfterMonths.get())
        assertEquals(
            listOf("testImplementation", "testRuntimeOnly", "testCompileClasspath", "testRuntimeClasspath"),
            ext.ignoreConfigurations.get(),
        )
    }

    @Test fun `lazy configuration propagates extension values to task`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val ext = project.extensions.getByType(DependencyPulseExtension::class.java)
        ext.failOnRed.set(true)
        ext.failOnError.set(true)
        ext.ignoreConfigurations.set(listOf("compileClasspath"))
        ext.thresholds { t ->
            t.yellowAfterMonths.set(6)
            t.redAfterMonths.set(18)
        }

        val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
        assertEquals(true, task.failOnRed.get())
        assertEquals(true, task.failOnError.get())
        assertEquals(listOf("compileClasspath"), task.ignoreConfigurations.get())
        assertEquals(6, task.yellowAfterMonths.get())
        assertEquals(18, task.redAfterMonths.get())
        assertEquals("https://central.sonatype.com", task.mavenCentralBaseUrl.get())
    }

    @Test fun `mavenCentralBaseUrl can be overridden via system property`() {
        System.setProperty("mavenCentralBaseUrl", "http://localhost:8080")
        try {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("com.billgonemad.dependency-pulse")
            val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
            assertEquals("http://localhost:8080", task.mavenCentralBaseUrl.get())
        } finally {
            System.clearProperty("mavenCentralBaseUrl")
        }
    }

    @Test fun `extension githubToken defaults to not present`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val ext = project.extensions.getByType(DependencyPulseExtension::class.java)
        assertFalse(ext.githubToken.isPresent)

        ext.githubToken.set("token-value")
        assertEquals("token-value", ext.githubToken.get())
    }

    @Test fun `githubToken is propagated to task when set`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val ext = project.extensions.getByType(DependencyPulseExtension::class.java)
        ext.githubToken.set("my-token")

        val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
        assertEquals("my-token", task.githubToken.get())
    }

    @Test fun `pomBaseUrl and githubApiBaseUrl default to the real APIs`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
        assertEquals("https://repo1.maven.org/maven2", task.pomBaseUrl.get())
        assertEquals("https://api.github.com", task.githubApiBaseUrl.get())
    }

    @Test fun `pomBaseUrl and githubApiBaseUrl can be overridden via system property`() {
        System.setProperty("pomBaseUrl", "http://localhost:8081")
        System.setProperty("githubApiBaseUrl", "http://localhost:8082")
        try {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("com.billgonemad.dependency-pulse")
            val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
            assertEquals("http://localhost:8081", task.pomBaseUrl.get())
            assertEquals("http://localhost:8082", task.githubApiBaseUrl.get())
        } finally {
            System.clearProperty("pomBaseUrl")
            System.clearProperty("githubApiBaseUrl")
        }
    }

    @Test fun `githubRateLimitService resolves to a usable shared instance`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.billgonemad.dependency-pulse")

        val task = project.tasks.getByName("dependencyPulse") as DependencyPulseTask
        val service = task.githubRateLimitService.get()

        assertNull(service.limitedUntil)
        val until =
            java.time.Instant
                .now()
                .plusSeconds(60)
        service.limitedUntil = until
        assertEquals(until, service.limitedUntil)
    }
}
