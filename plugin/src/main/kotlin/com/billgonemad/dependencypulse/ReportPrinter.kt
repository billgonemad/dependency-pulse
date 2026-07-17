package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit

private const val DAYS_PER_MONTH = 30

object ReportPrinter {
    // Defaults to VERBOSE (today's original always-print behavior), not DEFAULT, so the
    // ~16 pre-existing call sites in ReportPrinterTest that omit outputLevel keep compiling
    // and passing unchanged. The one production caller (DependencyPulseTask.run()) always
    // passes the resolved level explicitly — this default is a test-ergonomics convenience,
    // not a path that can silently ship reduced output.
    internal fun print(
        results: List<DependencyInfo>,
        now: Instant = Instant.now(),
        outputLevel: OutputLevel = OutputLevel.VERBOSE,
    ) {
        println("Dependency Pulse Report")
        println("=======================")
        if (outputLevel != OutputLevel.SUMMARY_ONLY) {
            results.forEach { dep ->
                val isStable = dep.isKnownStableWithSignals()
                val isPlainGreenInDefaultMode =
                    outputLevel == OutputLevel.DEFAULT &&
                        dep.status == DepStatus.GREEN &&
                        !isStable
                if (isPlainGreenInDefaultMode) return@forEach
                val emoji = selectEmoji(dep, isStable)
                println("$emoji ${dep.group}:${dep.artifact}:${dep.currentVersion}")
                printDetailLine(dep, now, isStable)
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

    private fun printDetailLine(
        dep: DependencyInfo,
        now: Instant,
        isStable: Boolean,
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
                    val stablePrefix = if (isStable) "Spec (stable) | " else ""
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
