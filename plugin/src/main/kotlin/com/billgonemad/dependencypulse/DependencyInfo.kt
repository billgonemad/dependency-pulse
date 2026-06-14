package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit

enum class DepStatus { GREEN, YELLOW, RED, UNKNOWN }

data class MavenSignals(
    val latestVersion: String,
    val latestReleaseDate: Instant,
)

data class GitHubSignals(
    val lastCommitDate: Instant,
    val isArchived: Boolean,
)

data class DependencyInfo(
    val group: String,
    val artifact: String,
    val currentVersion: String,
    val mavenSignals: MavenSignals?,
    val githubSignals: GitHubSignals?,
    val status: DepStatus,
    val errorMessage: String?,
    val javaxBlocker: Boolean = false,
)

private const val DAYS_PER_MONTH = 30

fun score(
    signals: MavenSignals?,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus {
    if (signals == null) return DepStatus.RED
    val days = ChronoUnit.DAYS.between(signals.latestReleaseDate, Instant.now())
    val months = (days / DAYS_PER_MONTH).toInt()
    return when {
        months >= redMonths -> DepStatus.RED
        months >= yellowMonths -> DepStatus.YELLOW
        else -> DepStatus.GREEN
    }
}
