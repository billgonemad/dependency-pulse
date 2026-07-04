package com.billgonemad.dependencypulse

import org.gradle.api.Project
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

class DependencyAnalyzer(
    private val client: MavenCentralClient,
    private val pomClient: PomClient,
    private val githubClient: GitHubClient,
) {
    private var resolver: Resolver = ::defaultResolver

    internal constructor(
        client: MavenCentralClient,
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
    ): List<DependencyInfo> {
        val coords = resolver(project, ignoreConfigurations).sortedWith(compareBy({ it.group }, { it.artifact }))
        val executor = Executors.newFixedThreadPool(minOf(CONCURRENCY, maxOf(coords.size, 1)))
        return try {
            coords
                .map { coord -> executor.submit(Callable { analyzeOne(coord, yellowAfterMonths, redAfterMonths) }) }
                .map { it.get() }
        } finally {
            executor.shutdown()
        }
    }

    private fun analyzeOne(
        coord: Coords,
        yellowAfterMonths: Int,
        redAfterMonths: Int,
    ): DependencyInfo {
        val (group, artifact, version) = coord
        val githubSignals = resolveGithubSignals(group, artifact, version)
        return try {
            val signals = client.fetchSignals(group, artifact, version)
            DependencyInfo(
                group = group,
                artifact = artifact,
                currentVersion = version,
                mavenSignals = signals,
                githubSignals = githubSignals,
                javaxBlocker = false,
                status = score(signals, githubSignals, yellowAfterMonths, redAfterMonths),
                errorMessage = null,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            DependencyInfo(
                group = group,
                artifact = artifact,
                currentVersion = version,
                mavenSignals = null,
                githubSignals = githubSignals,
                javaxBlocker = false,
                status = DepStatus.UNKNOWN,
                errorMessage = e.message,
            )
        }
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
