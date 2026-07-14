@file:Suppress("MatchingDeclarationName")

package com.billgonemad.dependencypulse

/**
 * A coordinate matches a `knownStableGroups` entry either exactly, as a
 * `group:artifact` coordinate (entry contains a `:`), or as a group-ID
 * prefix (`group.startsWith(entry)`) otherwise. Used to relabel spec/API
 * artifacts (e.g. `jakarta.*`, `javax.*`) that release infrequently by
 * design, so they aren't scored as abandoned (issue #60).
 */
internal fun matchesKnownStableGroup(
    coord: Coords,
    knownStableGroups: List<String>,
): Boolean =
    knownStableGroups.any { entry ->
        if (':' in entry) "${coord.group}:${coord.artifact}" == entry else coord.group.startsWith(entry)
    }
