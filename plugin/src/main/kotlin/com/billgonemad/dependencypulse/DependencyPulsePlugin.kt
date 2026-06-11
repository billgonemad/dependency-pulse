package com.billgonemad.dependencypulse

import org.gradle.api.Plugin
import org.gradle.api.Project

class DependencyPulsePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("dependencyPulse", DependencyPulseExtension::class.java)

        ext.failOnRed.convention(false)
        ext.failOnError.convention(false)
        ext.ignoreConfigurations.convention(
            listOf(
                "testImplementation",
                "testRuntimeOnly",
                "testCompileClasspath",
                "testRuntimeClasspath",
            ),
        )
        ext.thresholds.yellowAfterMonths.convention(DependencyPulseExtension.Thresholds.DEFAULT_YELLOW_AFTER_MONTHS)
        ext.thresholds.redAfterMonths.convention(DependencyPulseExtension.Thresholds.DEFAULT_RED_AFTER_MONTHS)

        val taskProvider = project.tasks.register("dependencyPulse", DependencyPulseTask::class.java)

        project.afterEvaluate {
            taskProvider.configure { task ->
                task.mavenCentralBaseUrl.set(
                    System.getProperty("mavenCentralBaseUrl", "https://search.maven.org"),
                )
                task.failOnRed.set(ext.failOnRed)
                task.failOnError.set(ext.failOnError)
                task.ignoreConfigurations.set(ext.ignoreConfigurations)
                task.yellowAfterMonths.set(ext.thresholds.yellowAfterMonths)
                task.redAfterMonths.set(ext.thresholds.redAfterMonths)
                ext.githubToken.orNull?.let { task.githubToken.set(it) }
            }
        }
    }
}
