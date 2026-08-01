package com.billgonemad.dependencypulse

import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

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
