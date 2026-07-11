package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit

enum class DepStatus { GREEN, YELLOW, RED, UNKNOWN }

data class MavenSignals(
    val latestVersion: String,
    val latestReleaseDate: Instant,
)

sealed class GitHubSignals {
    data class Found(
        val lastCommitDate: Instant?,
        val isArchived: Boolean,
    ) : GitHubSignals()

    object NoRepo : GitHubSignals()

    object RateLimited : GitHubSignals()

    object FetchFailed : GitHubSignals()
}

data class DependencyInfo(
    val group: String,
    val artifact: String,
    val currentVersion: String,
    val mavenSignals: MavenSignals?,
    val githubSignals: GitHubSignals,
    val status: DepStatus,
    val errorMessage: String?,
    val javaxBlocker: Boolean = false,
    val knownStable: Boolean = false,
)

private const val DAYS_PER_MONTH = 30

fun score(
    mavenSignals: MavenSignals?,
    githubSignals: GitHubSignals,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus {
    val mavenStatus = mavenStatus(mavenSignals, yellowMonths, redMonths)
    val githubStatus = githubStatus(githubSignals, yellowMonths, redMonths)
    return maxOf(mavenStatus, githubStatus)
}

private fun mavenStatus(
    signals: MavenSignals?,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus {
    if (signals == null) return DepStatus.RED
    return monthsStatus(signals.latestReleaseDate, yellowMonths, redMonths)
}

private fun githubStatus(
    signals: GitHubSignals,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus =
    when (signals) {
        is GitHubSignals.Found -> {
            when {
                signals.isArchived -> DepStatus.RED
                signals.lastCommitDate == null -> DepStatus.GREEN
                else -> monthsStatus(signals.lastCommitDate, yellowMonths, redMonths)
            }
        }

        GitHubSignals.NoRepo, GitHubSignals.RateLimited, GitHubSignals.FetchFailed -> {
            DepStatus.GREEN
        }
    }

private fun monthsStatus(
    date: Instant,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus {
    val days = ChronoUnit.DAYS.between(date, Instant.now())
    val months = (days / DAYS_PER_MONTH).toInt()
    return when {
        months >= redMonths -> DepStatus.RED
        months >= yellowMonths -> DepStatus.YELLOW
        else -> DepStatus.GREEN
    }
}
