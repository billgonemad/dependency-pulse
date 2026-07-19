package com.billgonemad.dependencypulse

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.net.http.HttpClient

internal data class DependencyAnalysisConfig(
    val pomBaseUrl: String,
    val githubApiBaseUrl: String,
    val githubToken: String?,
    val retryDelayMs: Long,
    val ignoreConfigurations: List<String>,
    val yellowAfterMonths: Int,
    val redAfterMonths: Int,
    val knownStableGroups: List<String>,
)

internal fun analyzeDependencies(
    project: Project,
    httpClient: HttpClient,
    rateLimitState: RateLimitState,
    config: DependencyAnalysisConfig,
): List<DependencyInfo> {
    val client =
        MavenMetadataClient(
            baseUrl = config.pomBaseUrl,
            httpClient = httpClient,
            retryDelayMs = config.retryDelayMs,
        )
    val pomClient = PomClient(baseUrl = config.pomBaseUrl, httpClient = httpClient)
    val githubClient =
        GitHubClient(
            baseUrl = config.githubApiBaseUrl,
            httpClient = httpClient,
            token = config.githubToken,
            rateLimitState = rateLimitState,
        )
    val analyzer = DependencyAnalyzer(client, pomClient, githubClient)
    return analyzer.analyze(
        project,
        config.ignoreConfigurations,
        config.yellowAfterMonths,
        config.redAfterMonths,
        config.knownStableGroups,
    )
}

@DisableCachingByDefault(because = "Queries live Maven Central API — results must not be cached across builds")
abstract class DependencyPulseTask : DefaultTask() {
    @get:Input
    abstract val failOnRed: Property<Boolean>

    @get:Input
    abstract val failOnError: Property<Boolean>

    @get:Input
    abstract val summaryOnly: Property<Boolean>

    @get:Input
    abstract val showGreen: Property<Boolean>

    @get:Internal
    @set:Option(option = "summary-only", description = "Print only the summary counts, no per-dependency lines")
    var cliSummaryOnly: Boolean = false

    @get:Internal
    @set:Option(option = "show-green", description = "Also print GREEN (up-to-date) dependencies")
    var cliShowGreen: Boolean = false

    @get:Input
    abstract val ignoreConfigurations: ListProperty<String>

    @get:Input
    abstract val knownStableGroups: ListProperty<String>

    @get:Input
    abstract val yellowAfterMonths: Property<Int>

    @get:Input
    abstract val redAfterMonths: Property<Int>

    @get:Input
    abstract val pomBaseUrl: Property<String>

    @get:Input
    abstract val githubApiBaseUrl: Property<String>

    @get:Input
    abstract val retryDelayMs: Property<Long>

    @get:Internal
    abstract val githubToken: Property<String>

    @get:Internal
    abstract val githubRateLimitService: Property<GitHubRateLimitService>

    @get:Internal
    abstract val httpClientService: Property<HttpClientService>

    @TaskAction
    fun run() {
        val outputLevel =
            resolveOutputLevel(
                cliSummaryOnly = cliSummaryOnly,
                cliShowGreen = cliShowGreen,
                extSummaryOnly = summaryOnly.get(),
                extShowGreen = showGreen.get(),
            )
        val httpClient = httpClientService.get().httpClient
        val results =
            analyzeDependencies(
                project = project,
                httpClient = httpClient,
                rateLimitState = githubRateLimitService.get(),
                config =
                    DependencyAnalysisConfig(
                        pomBaseUrl = pomBaseUrl.get(),
                        githubApiBaseUrl = githubApiBaseUrl.get(),
                        githubToken = githubToken.orNull,
                        retryDelayMs = retryDelayMs.get(),
                        ignoreConfigurations = ignoreConfigurations.get(),
                        yellowAfterMonths = yellowAfterMonths.get(),
                        redAfterMonths = redAfterMonths.get(),
                        knownStableGroups = knownStableGroups.get(),
                    ),
            )
        ReportPrinter.print(results, outputLevel = outputLevel)

        val hasUnexemptedRed = results.any { it.status == DepStatus.RED && !it.isKnownStableWithSignals() }
        if (failOnRed.get() && hasUnexemptedRed) {
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
