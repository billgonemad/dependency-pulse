package com.billgonemad.dependencypulse

import org.gradle.api.Plugin
import org.gradle.api.Project

private const val DEFAULT_RETRY_DELAY_MS = 1_000L

class DependencyPulsePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("dependencyPulse", DependencyPulseExtension::class.java)

        ext.failOnRed.convention(false)
        ext.failOnError.convention(false)
        ext.runOnCheck.convention(false)
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

        val rateLimitService =
            project.gradle.sharedServices.registerIfAbsent(
                "githubRateLimitService",
                GitHubRateLimitService::class.java,
            ) {}

        project.tasks.register("dependencyPulse", DependencyPulseTask::class.java) { task ->
            task.pomBaseUrl.set(
                project.providers
                    .systemProperty("pomBaseUrl")
                    .orElse("https://repo1.maven.org/maven2"),
            )
            task.githubApiBaseUrl.set(
                project.providers
                    .systemProperty("githubApiBaseUrl")
                    .orElse("https://api.github.com"),
            )
            task.retryDelayMs.set(
                project.providers
                    .systemProperty("mavenCentralRetryDelayMs")
                    .map { it.toLong() }
                    .orElse(DEFAULT_RETRY_DELAY_MS),
            )
            task.failOnRed.set(ext.failOnRed)
            task.failOnError.set(ext.failOnError)
            task.ignoreConfigurations.set(ext.ignoreConfigurations)
            task.yellowAfterMonths.set(ext.thresholds.yellowAfterMonths)
            task.redAfterMonths.set(ext.thresholds.redAfterMonths)
            task.githubToken.set(ext.githubToken)
            task.githubRateLimitService.set(rateLimitService)
            task.usesService(rateLimitService)
        }

        project.afterEvaluate {
            if (ext.runOnCheck.get() && project.tasks.findByName("check") != null) {
                project.tasks.named("check") {
                    it.dependsOn("dependencyPulse")
                }
            }
        }
    }
}
