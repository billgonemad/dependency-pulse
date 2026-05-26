package com.billgonemad.dependencypulse

import org.gradle.api.Project

private typealias Coords = Triple<String, String, String>

private typealias Resolver = (project: Project, ignoreConfigurations: List<String>) -> Set<Coords>

class DependencyAnalyzer(
    private val client: MavenCentralClient,
    private val resolver: Resolver =
        { project, ignore ->
            project.configurations
                .filter { it.isCanBeResolved && it.name !in ignore }
                .flatMap { it.resolvedConfiguration.resolvedArtifacts }
                .map {
                    Triple(
                        it.moduleVersion.id.group,
                        it.moduleVersion.id.name,
                        it.moduleVersion.id.version,
                    )
                }.toSet()
        },
) {
    fun analyze(
        project: Project,
        ignoreConfigurations: List<String>,
        yellowAfterMonths: Int,
        redAfterMonths: Int,
    ): List<DependencyInfo> =
        resolver(project, ignoreConfigurations)
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (group, artifact, version) ->
                try {
                    val signals = client.fetchSignals(group, artifact, version)
                    DependencyInfo(
                        group = group,
                        artifact = artifact,
                        currentVersion = version,
                        mavenSignals = signals,
                        githubSignals = null,
                        javaxBlocker = false,
                        status = score(signals, yellowAfterMonths, redAfterMonths),
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
                        githubSignals = null,
                        javaxBlocker = false,
                        status = DepStatus.UNKNOWN,
                        errorMessage = e.message,
                    )
                }
            }
}
