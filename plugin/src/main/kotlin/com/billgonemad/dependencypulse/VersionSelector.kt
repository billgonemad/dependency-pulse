@file:Suppress("MatchingDeclarationName")

package com.billgonemad.dependencypulse

private val PRE_RELEASE_QUALIFIERS =
    setOf("alpha", "beta", "rc", "cr", "m", "milestone", "snapshot", "preview", "dev", "eap", "pre")

private val TOKEN_DELIMITERS = Regex("[-.+]")
private val TRAILING_DIGITS = Regex("\\d+$")

/**
 * A version is a pre-release if any of its delimiter-separated tokens, after
 * stripping trailing digits, equals a known qualifier (e.g. `M1` -> `m`,
 * `rc2` -> `rc`). Plain numeric tokens never match, so `4.12.0`,
 * `1.0.0.RELEASE`, and `2.0-Final` are stable.
 */
internal fun isPreRelease(version: String): Boolean =
    version.split(TOKEN_DELIMITERS).any { token ->
        TRAILING_DIGITS.replaceFirst(token, "").lowercase() in PRE_RELEASE_QUALIFIERS
    }

/**
 * Picks the version to treat as "latest" from a maven-metadata.xml-derived
 * [orderedVersions] list (oldest to newest, per Maven's deploy-time append
 * convention) and the metadata's own [latest] tag (newest of any kind,
 * including pre-releases):
 * - if [currentVersion] is itself a pre-release, [latest] itself;
 * - otherwise the newest stable version (scanning [orderedVersions] from the
 *   end), falling back to [latest] when no stable version exists.
 * Returns null if [orderedVersions] is empty.
 */
internal fun selectLatestVersion(
    latest: String,
    orderedVersions: List<String>,
    currentVersion: String,
): String? =
    if (orderedVersions.isEmpty()) {
        null
    } else if (isPreRelease(currentVersion)) {
        latest
    } else {
        orderedVersions.lastOrNull { !isPreRelease(it) } ?: latest
    }
