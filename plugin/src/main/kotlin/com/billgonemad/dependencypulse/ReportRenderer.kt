package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit

private const val DAYS_PER_MONTH = 30

object ReportRenderer {
    // Defaults to VERBOSE (every dependency) so unit tests that exercise line formatting
    // don't need to opt in to green lines. The one production caller
    // (DependencyPulseTask.run()) always passes the resolved level explicitly.
    internal fun render(
        results: List<DependencyInfo>,
        now: Instant = Instant.now(),
        outputLevel: OutputLevel = OutputLevel.VERBOSE,
    ): String =
        buildString {
            appendLine("Dependency Pulse Report")
            appendLine("=======================")
            if (outputLevel != OutputLevel.SUMMARY_ONLY) {
                results.forEach { dep ->
                    val isStable = dep.isKnownStableWithSignals()
                    val isPlainGreenInDefaultMode =
                        outputLevel == OutputLevel.DEFAULT &&
                            dep.status == DepStatus.GREEN &&
                            !isStable
                    if (isPlainGreenInDefaultMode) return@forEach
                    val emoji = selectEmoji(dep, isStable)
                    appendLine("$emoji ${dep.group}:${dep.artifact}:${dep.currentVersion}")
                    appendDetailLine(dep, now, isStable)
                    appendGithubLine(dep, now)
                    appendLine()
                }
            }
            appendLine("=======================")
            val green = results.count { it.status == DepStatus.GREEN && !it.isKnownStableWithSignals() }
            val yellow = results.count { it.status == DepStatus.YELLOW && !it.isKnownStableWithSignals() }
            val red = results.count { it.isUnexemptedRed() }
            val unknown = results.count { it.status == DepStatus.UNKNOWN && !it.isKnownStableWithSignals() }
            val stable = results.count { it.isKnownStableWithSignals() }
            appendLine(
                "${results.size} dependencies scanned. $red red, $yellow yellow, $green green, " +
                    "$unknown unknown, $stable stable.",
            )
            appendGithubSkippedLine(results)
            appendGithubNoCommitDataLine(results)
        }.removeSuffix("\n")

    private fun StringBuilder.appendGithubSkippedLine(results: List<DependencyInfo>) {
        val rateLimited = results.count { it.githubSignals == GitHubSignals.RateLimited }
        val fetchFailed = results.count { it.githubSignals == GitHubSignals.FetchFailed }
        val skipped = rateLimited + fetchFailed
        if (skipped == 0) return
        val reasons =
            listOfNotNull(
                "$rateLimited rate limited".takeIf { rateLimited > 0 },
                "$fetchFailed fetch failed".takeIf { fetchFailed > 0 },
            ).joinToString(", ")
        appendLine("$skipped GitHub checks skipped ($reasons).")
    }

    private fun StringBuilder.appendGithubNoCommitDataLine(results: List<DependencyInfo>) {
        val noCommitData =
            results.count {
                val signals = it.githubSignals
                signals is GitHubSignals.Found && signals.lastCommitDate == null && !signals.isArchived
            }
        if (noCommitData == 0) return
        appendLine("$noCommitData GitHub repos found with no commit data available.")
    }

    private fun selectEmoji(
        dep: DependencyInfo,
        isStable: Boolean,
    ): String =
        if (isStable) {
            "📘"
        } else {
            when (dep.status) {
                DepStatus.GREEN -> "✅"
                DepStatus.YELLOW -> "⚠️"
                DepStatus.RED -> "🔴"
                DepStatus.UNKNOWN -> "❓"
            }
        }

    private fun StringBuilder.appendDetailLine(
        dep: DependencyInfo,
        now: Instant,
        isStable: Boolean,
    ) {
        when {
            dep.status == DepStatus.UNKNOWN -> {
                appendLine("   Maven Central unavailable — skipped (set failOnError=true to fail the build)")
            }

            dep.status == DepStatus.RED && dep.mavenSignals == null -> {
                appendLine("   Artifact no longer published to Maven Central")
            }

            else -> {
                val signals = dep.mavenSignals
                if (signals != null) {
                    val months = monthsAgo(signals.latestReleaseDate, now)
                    val active = if (dep.status == DepStatus.GREEN) " | Active" else ""
                    val stablePrefix = if (isStable) "Spec (stable) | " else ""
                    val latestLabel =
                        if (signals.verified) signals.latestVersion else "unknown (repository metadata incomplete)"
                    appendLine("   ${stablePrefix}Latest: $latestLabel | Released: $months months ago$active")
                } else {
                    appendLine("   No Maven Central data available")
                }
            }
        }
    }

    private fun StringBuilder.appendGithubLine(
        dep: DependencyInfo,
        now: Instant,
    ) {
        when (val signals = dep.githubSignals) {
            is GitHubSignals.Found -> {
                when {
                    signals.isArchived -> {
                        appendLine("   GitHub: Repo archived")
                    }

                    signals.lastCommitDate != null -> {
                        val months = monthsAgo(signals.lastCommitDate, now)
                        appendLine("   GitHub: Last commit $months months ago")
                    }

                    else -> {
                        appendLine("   GitHub: repo found, no commit data available")
                    }
                }
            }

            GitHubSignals.RateLimited -> {
                appendLine("   GitHub: check skipped (rate limited)")
            }

            GitHubSignals.FetchFailed -> {
                appendLine("   GitHub: check skipped (fetch failed)")
            }

            GitHubSignals.NoRepo -> {}
        }
    }

    private fun monthsAgo(
        date: Instant,
        now: Instant,
    ): Int = (ChronoUnit.DAYS.between(date, now) / DAYS_PER_MONTH).toInt()
}
