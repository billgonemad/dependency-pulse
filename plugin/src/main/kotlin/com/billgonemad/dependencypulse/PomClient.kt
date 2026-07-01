package com.billgonemad.dependencypulse

private val GITHUB_URL_PATTERN = Regex("""github\.com[/:]+([\w.-]+)/([\w.-]+)""")

internal fun normalizeGitHubUrl(rawUrl: String?): String? {
    val match = rawUrl?.let { GITHUB_URL_PATTERN.find(it) } ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2].removeSuffix(".git")
    return "$owner/$repo"
}
