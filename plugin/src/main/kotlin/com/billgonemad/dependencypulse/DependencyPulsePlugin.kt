package com.billgonemad.dependencypulse

import org.gradle.api.Plugin
import org.gradle.api.Project

class DependencyPulsePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("dependencyPulse", DependencyPulseExtension::class.java)
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
                ext.githubToken?.let { task.githubToken.set(it) }
            }
        }
    }
}
