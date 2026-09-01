package com.billgonemad.dependencypulse

/**
 * Generates candidate Maven version strings from a GitHub release tag. Real
 * tag-naming schemes vary enough (v9.7.0, v_2.0.18, jackson-databind-3.2.1)
 * that no single regex covers them; each rule either matches and strips its
 * prefix or doesn't apply, and every generated candidate gets verified by an
 * actual Maven POM probe by the caller — this function never claims a
 * candidate is correct, only plausible.
 */
internal fun normalizeTagToVersionCandidates(
    tag: String,
    artifactId: String,
): List<String> {
    val candidates = linkedSetOf(tag)
    if (tag.startsWith("v_")) candidates += tag.removePrefix("v_")
    if (tag.length > 1 && tag[0] == 'v' && tag[1].isDigit()) candidates += tag.removePrefix("v")
    val artifactPrefix = "$artifactId-"
    if (tag.startsWith(artifactPrefix)) candidates += tag.removePrefix(artifactPrefix)
    if (tag.startsWith("release-")) candidates += tag.removePrefix("release-")
    return candidates.toList()
}
