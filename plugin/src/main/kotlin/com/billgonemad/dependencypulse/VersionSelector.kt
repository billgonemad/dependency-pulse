@file:Suppress("MatchingDeclarationName")

package com.billgonemad.dependencypulse

data class VersionEntry(
    val version: String,
    val timestamp: Long,
)

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
fun isPreRelease(version: String): Boolean =
    version.split(TOKEN_DELIMITERS).any { token ->
        TRAILING_DIGITS.replaceFirst(token, "").lowercase() in PRE_RELEASE_QUALIFIERS
    }

/**
 * Picks the version to treat as "latest":
 * - if [currentVersion] is itself a pre-release, the newest version of any kind;
 * - otherwise the newest stable version, falling back to the newest pre-release
 *   when no stable version exists.
 * Newest is by [VersionEntry.timestamp]. Returns null for an empty list.
 */
fun selectLatest(
    versions: List<VersionEntry>,
    currentVersion: String,
): VersionEntry? =
    versions.maxByOrNull { it.timestamp }?.let { newestOverall ->
        if (isPreRelease(currentVersion)) {
            newestOverall
        } else {
            versions.filterNot { isPreRelease(it.version) }.maxByOrNull { it.timestamp } ?: newestOverall
        }
    }
