package com.billgonemad.dependencypulse

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Queries live Maven Central API — results must not be cached across builds")
abstract class DependencyPulseTask : DefaultTask() {
    @get:Input
    abstract val failOnRed: Property<Boolean>

    @get:Input
    abstract val failOnError: Property<Boolean>

    @get:Input
    abstract val ignoreConfigurations: ListProperty<String>

    @get:Input
    abstract val yellowAfterMonths: Property<Int>

    @get:Input
    abstract val redAfterMonths: Property<Int>

    @get:Input
    abstract val mavenCentralBaseUrl: Property<String>

    @get:Input
    abstract val retryDelayMs: Property<Long>

    @get:Input
    @get:Optional
    abstract val githubToken: Property<String>

    @TaskAction
    fun run() {
        val client = MavenCentralClient(baseUrl = mavenCentralBaseUrl.get(), retryDelayMs = retryDelayMs.get())
        val pomClient = PomClient()
        val githubClient = GitHubClient(token = githubToken.orNull)
        val analyzer = DependencyAnalyzer(client, pomClient, githubClient)
        val results =
            analyzer.analyze(
                project,
                ignoreConfigurations.get(),
                yellowAfterMonths.get(),
                redAfterMonths.get(),
            )
        ReportPrinter.print(results)

        if (failOnRed.get() && results.any { it.status == DepStatus.RED }) {
            throw GradleException("dependency-pulse: one or more RED dependencies detected.")
        }
        if (failOnError.get() && results.any { it.status == DepStatus.UNKNOWN }) {
            throw GradleException(
                "dependency-pulse: one or more dependencies could not be checked " +
                    "(set failOnError=false to suppress).",
            )
        }
    }
}
