package com.billgonemad.dependencypulse

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

internal fun resolveCoordinates(
    project: Project,
    ignoreConfigurations: List<String>,
    onResolutionFailure: (configurationName: String, error: Exception) -> Unit = { name, error ->
        project.logger.warn(
            "dependencyPulse: could not resolve configuration '$name'; its dependencies are omitted from this report",
            error,
        )
    },
): Set<Coords> =
    project.configurations
        .filter { it.isCanBeResolved && it.name !in ignoreConfigurations }
        .flatMap { configuration ->
            try {
                configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                    (component.id as? ModuleComponentIdentifier)?.let {
                        Coords(it.group, it.module, it.version)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                onResolutionFailure(configuration.name, e)
                emptyList()
            }
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
