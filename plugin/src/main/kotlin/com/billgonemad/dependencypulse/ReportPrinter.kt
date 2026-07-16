package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit

private const val DAYS_PER_MONTH = 30

object ReportPrinter {
    internal fun print(
        results: List<DependencyInfo>,
        now: Instant = Instant.now(),
        outputLevel: OutputLevel = OutputLevel.VERBOSE,
    ) {
        println("Dependency Pulse Report")
        println("=======================")
        if (outputLevel != OutputLevel.SUMMARY_ONLY) {
            results.forEach { dep ->
                val isPlainGreenInDefaultMode =
                    outputLevel == OutputLevel.DEFAULT &&
                        dep.status == DepStatus.GREEN &&
                        !dep.isKnownStableWithSignals()
                if (isPlainGreenInDefaultMode) return@forEach
                val emoji = selectEmoji(dep)
                println("$emoji ${dep.group}:${dep.artifact}:${dep.currentVersion}")
                printDetailLine(dep, now)
                printGithubLine(dep, now)
                println()
            }
        }
        println("=======================")
        val green = results.count { it.status == DepStatus.GREEN && !it.isKnownStableWithSignals() }
        val yellow = results.count { it.status == DepStatus.YELLOW && !it.isKnownStableWithSignals() }
        val red = results.count { it.status == DepStatus.RED && !it.isKnownStableWithSignals() }
        val unknown = results.count { it.status == DepStatus.UNKNOWN && !it.isKnownStableWithSignals() }
        val stable = results.count { it.isKnownStableWithSignals() }
        println(
            "${results.size} dependencies scanned. $red red, $yellow yellow, $green green, " +
                "$unknown unknown, $stable stable.",
        )
    }

    private fun selectEmoji(dep: DependencyInfo): String =
        if (dep.isKnownStableWithSignals()) {
            "📘"
        } else {
            when (dep.status) {
                DepStatus.GREEN -> "✅"
                DepStatus.YELLOW -> "⚠️"
                DepStatus.RED -> "🔴"
                DepStatus.UNKNOWN -> "❓"
            }
        }

    private fun printDetailLine(
        dep: DependencyInfo,
        now: Instant,
    ) {
        when {
            dep.status == DepStatus.UNKNOWN -> {
                println("   Maven Central unavailable — skipped (set failOnError=true to fail the build)")
            }

            dep.status == DepStatus.RED && dep.mavenSignals == null -> {
                println("   Artifact no longer published to Maven Central")
            }

            else -> {
                val signals = dep.mavenSignals
                if (signals != null) {
                    val months = monthsAgo(signals.latestReleaseDate, now)
                    val active = if (dep.status == DepStatus.GREEN) " | Active" else ""
                    val stablePrefix = if (dep.isKnownStableWithSignals()) "Spec (stable) | " else ""
                    println("   ${stablePrefix}Latest: ${signals.latestVersion} | Released: $months months ago$active")
                } else {
                    println("   No Maven Central data available")
                }
            }
        }
    }

    private fun printGithubLine(
        dep: DependencyInfo,
        now: Instant,
    ) {
        val signals = dep.githubSignals
        if (signals !is GitHubSignals.Found) return
        when {
            signals.isArchived -> {
                println("   GitHub: Repo archived")
            }

            signals.lastCommitDate != null -> {
                val months = monthsAgo(signals.lastCommitDate, now)
                println("   GitHub: Last commit $months months ago")
            }
        }
    }

    private fun monthsAgo(
        date: Instant,
        now: Instant,
    ): Int = (ChronoUnit.DAYS.between(date, now) / DAYS_PER_MONTH).toInt()
}
