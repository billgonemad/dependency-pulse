package com.billgonemad.dependencypulse

import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val CONCURRENCY = 8

internal data class Coords(
    val group: String,
    val artifact: String,
    val version: String,
)

internal typealias Resolver = (project: Project, ignoreConfigurations: List<String>) -> Set<Coords>

private fun defaultResolver(
    project: Project,
    ignore: List<String>,
): Set<Coords> =
    project.configurations
        .filter { it.isCanBeResolved && it.name !in ignore }
        .flatMap { it.resolvedConfiguration.lenientConfiguration.artifacts }
        .map {
            Coords(
                it.moduleVersion.id.group,
                it.moduleVersion.id.name,
                it.moduleVersion.id.version,
            )
        }.toSet()

internal fun buildRepoUrls(
    pomBaseUrl: String,
    repositories: List<ArtifactRepository>,
): List<String> {
    val declared =
        repositories
            .filterIsInstance<MavenArtifactRepository>()
            .filter { it.url.scheme == "http" || it.url.scheme == "https" }
            .map { it.url.toString() }
    val all = (listOf(pomBaseUrl) + declared).map { it.trimEnd('/') }
    val seen = mutableSetOf<String>()
    return all.filter { seen.add(it) }
}

class DependencyAnalyzer(
    private val client: MavenMetadataClient,
    private val pomClient: PomClient,
    private val githubClient: GitHubClient,
) {
    private var resolver: Resolver = ::defaultResolver

    internal constructor(
        client: MavenMetadataClient,
        pomClient: PomClient,
        githubClient: GitHubClient,
        resolver: Resolver,
    ) : this(client, pomClient, githubClient) {
        this.resolver = resolver
    }

    fun analyze(
        project: Project,
        ignoreConfigurations: List<String>,
        yellowAfterMonths: Int,
        redAfterMonths: Int,
        knownStableGroups: List<String>,
    ): List<DependencyInfo> {
        val coords = resolver(project, ignoreConfigurations).sortedWith(compareBy({ it.group }, { it.artifact }))
        val repoUrls = buildRepoUrls(client.baseUrl, project.repositories)
        val executor = Executors.newFixedThreadPool(minOf(CONCURRENCY, maxOf(coords.size, 1)))
        return try {
            coords
                .map { coord ->
                    executor.submit(
                        Callable { analyzeOne(coord, repoUrls, yellowAfterMonths, redAfterMonths, knownStableGroups) },
                    )
                }.map { it.get() }
        } finally {
            executor.shutdown()
        }
    }

    private fun analyzeOne(
        coord: Coords,
        repoUrls: List<String>,
        yellowAfterMonths: Int,
        redAfterMonths: Int,
        knownStableGroups: List<String>,
    ): DependencyInfo {
        val (group, artifact, version) = coord
        val githubSignals = resolveGithubSignals(group, artifact, version)
        val knownStable = matchesKnownStableGroup(coord, knownStableGroups)
        val walkResult = walkRepos(coord, repoUrls, yellowAfterMonths, redAfterMonths)
        return when (walkResult) {
            is WalkResult.Found -> {
                DependencyInfo(
                    group = group,
                    artifact = artifact,
                    currentVersion = version,
                    mavenSignals = walkResult.signals,
                    githubSignals = githubSignals,
                    javaxBlocker = false,
                    status = score(walkResult.signals, githubSignals, yellowAfterMonths, redAfterMonths),
                    errorMessage = null,
                    knownStable = knownStable,
                )
            }

            WalkResult.NotPublished -> {
                DependencyInfo(
                    group = group,
                    artifact = artifact,
                    currentVersion = version,
                    mavenSignals = null,
                    githubSignals = githubSignals,
                    javaxBlocker = false,
                    status = score(null, githubSignals, yellowAfterMonths, redAfterMonths),
                    errorMessage = null,
                    knownStable = knownStable,
                )
            }

            is WalkResult.Unresolvable -> {
                DependencyInfo(
                    group = group,
                    artifact = artifact,
                    currentVersion = version,
                    mavenSignals = null,
                    githubSignals = githubSignals,
                    javaxBlocker = false,
                    status = unresolvableStatus(githubSignals, yellowAfterMonths, redAfterMonths),
                    errorMessage = walkResult.message,
                    knownStable = knownStable,
                )
            }
        }
    }

    private fun walkRepos(
        coord: Coords,
        repoUrls: List<String>,
        yellowAfterMonths: Int,
        redAfterMonths: Int,
    ): WalkResult {
        var bestSignals: MavenSignals? = null
        var anyThrew = false
        var firstError: String? = null
        for (repoUrl in repoUrls) {
            when (val attempt = attemptFetch(coord, repoUrl)) {
                is RepoAttempt.Signals -> {
                    val isFresher =
                        bestSignals == null || attempt.signals.latestReleaseDate.isAfter(bestSignals.latestReleaseDate)
                    if (isFresher) {
                        bestSignals = attempt.signals
                    }
                    if (mavenStatus(attempt.signals, yellowAfterMonths, redAfterMonths) == DepStatus.GREEN) break
                }

                RepoAttempt.NotFound -> {}

                is RepoAttempt.Threw -> {
                    anyThrew = true
                    if (firstError == null) firstError = attempt.message
                }
            }
        }
        return when {
            bestSignals != null -> WalkResult.Found(bestSignals)
            !anyThrew -> WalkResult.NotPublished
            else -> WalkResult.Unresolvable(firstError)
        }
    }

    private fun attemptFetch(
        coord: Coords,
        repoUrl: String,
    ): RepoAttempt {
        val (group, artifact, version) = coord
        return try {
            val signals = client.fetchSignals(group, artifact, version, repoUrl)
            if (signals != null) RepoAttempt.Signals(signals) else RepoAttempt.NotFound
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            RepoAttempt.Threw(e.message)
        }
    }

    private sealed class WalkResult {
        data class Found(
            val signals: MavenSignals,
        ) : WalkResult()

        object NotPublished : WalkResult()

        data class Unresolvable(
            val message: String?,
        ) : WalkResult()
    }

    private sealed class RepoAttempt {
        data class Signals(
            val signals: MavenSignals,
        ) : RepoAttempt()

        object NotFound : RepoAttempt()

        data class Threw(
            val message: String?,
        ) : RepoAttempt()
    }

    private fun resolveGithubSignals(
        group: String,
        artifact: String,
        version: String,
    ): GitHubSignals =
        try {
            val repo = pomClient.fetchGitHubRepo(group, artifact, version)
            repo?.let { githubClient.fetchSignals(it) } ?: GitHubSignals.NoRepo
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            GitHubSignals.FetchFailed
        }
}
