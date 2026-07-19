package com.billgonemad.dependencypulse

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

private const val DEFAULT_RETRY_DELAY_MS = 1_000L

class DependencyPulsePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("dependencyPulse", DependencyPulseExtension::class.java)
        applyConventions(ext)

        val rateLimitService =
            project.gradle.sharedServices.registerIfAbsent(
                "githubRateLimitService",
                GitHubRateLimitService::class.java,
            ) {}

        project.tasks.register("dependencyPulse", DependencyPulseTask::class.java) { task ->
            configureTask(task, project, ext, rateLimitService)
        }

        project.afterEvaluate {
            if (ext.runOnCheck.get() && project.tasks.findByName("check") != null) {
                project.tasks.named("check") {
                    it.dependsOn("dependencyPulse")
                }
            }
        }
    }

    private fun applyConventions(ext: DependencyPulseExtension) {
        ext.failOnRed.convention(false)
        ext.failOnError.convention(false)
        ext.runOnCheck.convention(false)
        ext.summaryOnly.convention(false)
        ext.showGreen.convention(false)
        ext.ignoreConfigurations.convention(
            listOf(
                "testImplementation",
                "testRuntimeOnly",
                "testCompileClasspath",
                "testRuntimeClasspath",
            ),
        )
        ext.knownStableGroups.convention(listOf("jakarta.", "javax."))
        ext.thresholds.yellowAfterMonths.convention(DependencyPulseExtension.Thresholds.DEFAULT_YELLOW_AFTER_MONTHS)
        ext.thresholds.redAfterMonths.convention(DependencyPulseExtension.Thresholds.DEFAULT_RED_AFTER_MONTHS)
    }

    private fun configureTask(
        task: DependencyPulseTask,
        project: Project,
        ext: DependencyPulseExtension,
        rateLimitService: Provider<GitHubRateLimitService>,
    ) {
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
        task.summaryOnly.set(ext.summaryOnly)
        task.showGreen.set(ext.showGreen)
        task.ignoreConfigurations.set(ext.ignoreConfigurations)
        task.knownStableGroups.set(ext.knownStableGroups)
        task.yellowAfterMonths.set(ext.thresholds.yellowAfterMonths)
        task.redAfterMonths.set(ext.thresholds.redAfterMonths)
        task.githubToken.set(ext.githubToken)
        task.githubRateLimitService.set(rateLimitService)
        task.usesService(rateLimitService)
    }
}
