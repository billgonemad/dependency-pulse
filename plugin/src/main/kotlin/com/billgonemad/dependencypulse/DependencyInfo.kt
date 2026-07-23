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

internal fun DependencyInfo.isKnownStableWithSignals(): Boolean {
    val archived = (githubSignals as? GitHubSignals.Found)?.isArchived == true
    return knownStable && mavenSignals != null && !archived
}

private const val DAYS_PER_MONTH = 30

fun score(
    mavenSignals: MavenSignals?,
    githubSignals: GitHubSignals,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus {
    val mavenStatus = mavenStatus(mavenSignals, yellowMonths, redMonths)
    val githubStatus = githubStatus(githubSignals, yellowMonths, redMonths)
    return combineStatuses(mavenStatus, githubStatus)
}

internal fun combineStatuses(
    a: DepStatus,
    b: DepStatus,
): DepStatus = if (severityOf(a) >= severityOf(b)) a else b

private const val SEVERITY_GREEN = 0
private const val SEVERITY_UNKNOWN = 1
private const val SEVERITY_YELLOW = 2
private const val SEVERITY_RED = 3

// Exhaustive `when` (no else) — a future DepStatus left unranked fails the build, not silently mis-sorts.
private fun severityOf(status: DepStatus): Int =
    when (status) {
        DepStatus.GREEN -> SEVERITY_GREEN
        DepStatus.UNKNOWN -> SEVERITY_UNKNOWN
        DepStatus.YELLOW -> SEVERITY_YELLOW
        DepStatus.RED -> SEVERITY_RED
    }

internal fun mavenStatus(
    signals: MavenSignals?,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus {
    if (signals == null) return DepStatus.RED
    return monthsStatus(signals.latestReleaseDate, yellowMonths, redMonths)
}

// For the "Maven walk couldn't resolve anything" case: UNKNOWN sits between GREEN and YELLOW in
// severity, so combining it with githubStatus lets a real GitHub RED/YELLOW signal still surface,
// while a GREEN/no-repo GitHub signal correctly leaves the overall verdict at UNKNOWN rather than
// falsely implying confidence the Maven side never had.
internal fun unresolvableStatus(
    githubSignals: GitHubSignals,
    yellowMonths: Int,
    redMonths: Int,
): DepStatus = combineStatuses(DepStatus.UNKNOWN, githubStatus(githubSignals, yellowMonths, redMonths))

internal fun githubStatus(
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
