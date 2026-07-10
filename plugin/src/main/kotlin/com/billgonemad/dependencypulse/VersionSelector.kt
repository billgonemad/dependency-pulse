@file:Suppress("MatchingDeclarationName")

package com.billgonemad.dependencypulse

private val PRE_RELEASE_QUALIFIERS =
    setOf("alpha", "beta", "rc", "cr", "m", "milestone", "snapshot", "preview", "dev", "eap", "pre")

private val TOKEN_DELIMITERS = Regex("[-.+]")
private val TRAILING_DIGITS = Regex("\\d+$")
private val TIMESTAMP_VERSION = Regex("^\\d{8}(\\.\\d{6})?$")

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
 * A version is a Maven legacy timestamp build if it's purely numeric and
 * date-shaped: an 8-digit `YYYYMMDD`, optionally followed by a 6-digit
 * `.HHMMSS`/`.NNNNNN` suffix (e.g. `20040616`, `20030418.083655`). These are
 * artifact-registration timestamps, not semantic releases, but carry no
 * qualifier token so [isPreRelease] doesn't catch them (issue #59).
 *
 * Known limitation: this only matches a version string that is *entirely*
 * the timestamp. Some Commons-era artifacts (e.g. `commons-collections`'
 * own `2.0.20020914.015953`) prefix the timestamp with a real version
 * (`<prefix>.<YYYYMMDD>.<HHMMSS>`) instead of replacing it outright; those
 * aren't matched here. Left unhandled deliberately — matching a substring
 * risks false-positiving on legitimate versions that happen to contain 8
 * consecutive digits, and no artifact checked while fixing #59 has this
 * shape as its last (i.e. "latest") registered version. Revisit if a
 * real-world case surfaces where it does.
 */
internal fun isTimestampVersion(version: String): Boolean = TIMESTAMP_VERSION.matches(version)

/**
 * Picks the version to treat as "latest" from a maven-metadata.xml-derived
 * [orderedVersions] list (oldest to newest, per Maven's deploy-time append
 * convention) and the metadata's own [latest] tag (newest of any kind,
 * including pre-releases and timestamp builds):
 * - if [currentVersion] is itself a pre-release, [latest] itself;
 * - otherwise the newest stable version (scanning [orderedVersions] from the
 *   end, skipping pre-releases and timestamp-shaped builds), falling back to
 *   [latest] when no stable version exists.
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
        orderedVersions.lastOrNull { !isPreRelease(it) && !isTimestampVersion(it) } ?: latest
    }
