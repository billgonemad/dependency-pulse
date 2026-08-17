package com.billgonemad.dependencypulse

import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val CONCURRENCY = 8
private const val MAX_PARENT_DEPTH = 5

internal class DependencyAnalyzer(
    private val client: MavenMetadataClient,
    private val pomClient: PomClient,
    private val githubClient: GitHubClient,
) {
    fun analyze(
        coords: Set<Coords>,
        repoUrls: List<String>,
        yellowAfterMonths: Int,
        redAfterMonths: Int,
        knownStableGroups: List<String>,
    ): List<DependencyInfo> {
        val sortedCoords = coords.sortedWith(compareBy({ it.group }, { it.artifact }))
        val executor = Executors.newFixedThreadPool(minOf(CONCURRENCY, maxOf(sortedCoords.size, 1)))
        return try {
            sortedCoords
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
        val githubSignals = resolveGithubSignals(coord, repoUrls)
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
        coord: Coords,
        repoUrls: List<String>,
    ): GitHubSignals =
        try {
            val repo = resolveGithubRepo(coord, repoUrls)
            repo?.let { githubClient.fetchSignals(it) } ?: GitHubSignals.NoRepo
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            GitHubSignals.FetchFailed
        }

    private fun resolveGithubRepo(
        coord: Coords,
        repoUrls: List<String>,
    ): String? {
        var candidate = coord
        var result: String? = null
        var done = false
        repeat(MAX_PARENT_DEPTH) {
            if (!done) {
                when (val fetch = fetchPomAcrossRepos(candidate, repoUrls)) {
                    is PomFetch.Success -> {
                        when {
                            fetch.githubRepo != null -> {
                                result = fetch.githubRepo
                                done = true
                            }

                            fetch.parentCoords != null -> {
                                candidate = fetch.parentCoords
                            }

                            else -> {
                                done = true
                            }
                        }
                    }

                    PomFetch.NotFound -> {
                        done = true
                    }
                }
            }
        }
        return result
    }

    // 404/not-found keeps walking to the next declared repo; a resolved (200) POM stops the walk
    // and is treated as the authoritative answer for this GAV, whether or not it carries an scm
    // link — see the walk-termination discussion on #115. A Success with neither an scm link nor
    // a parent, and a NotFound at every declared repo, both mean "nothing left to climb to" and
    // are handled identically by the caller.
    private fun fetchPomAcrossRepos(
        coord: Coords,
        repoUrls: List<String>,
    ): PomFetch {
        val (group, artifact, version) = coord
        var firstError: Exception? = null
        for (repoUrl in repoUrls) {
            try {
                val fetch = pomClient.lookupGitHubRepo(group, artifact, version, repoUrl)
                if (fetch is PomFetch.Success) return fetch
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (firstError == null) firstError = e
            }
        }
        firstError?.let { throw it }
        return PomFetch.NotFound
    }
}
