package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit

private const val DAYS_PER_MONTH = 30

object ReportPrinter {
    fun print(
        results: List<DependencyInfo>,
        now: Instant = Instant.now(),
    ) {
        println("Dependency Pulse Report")
        println("=======================")
        results.forEach { dep ->
            val emoji =
                when (dep.status) {
                    DepStatus.GREEN -> "✅"
                    DepStatus.YELLOW -> "⚠️"
                    DepStatus.RED -> "🔴"
                    DepStatus.UNKNOWN -> "❓"
                }
            println("$emoji ${dep.group}:${dep.artifact}:${dep.currentVersion}")
            when {
                dep.status == DepStatus.UNKNOWN -> {
                    println("   Maven Central unavailable — skipped (set failOnError=true to fail the build)")
                }

                dep.status == DepStatus.RED && dep.mavenSignals == null -> {
                    println("   Artifact no longer published to Maven Central")
                }

                else -> {
                    val signals = dep.mavenSignals ?: return@forEach
                    val months = monthsAgo(signals.latestReleaseDate, now)
                    val active = if (dep.status == DepStatus.GREEN) " | Active" else ""
                    println("   Latest: ${signals.latestVersion} | Released: $months months ago$active")
                }
            }
            println()
        }
        println("=======================")
        val green = results.count { it.status == DepStatus.GREEN }
        val yellow = results.count { it.status == DepStatus.YELLOW }
        val red = results.count { it.status == DepStatus.RED }
        val unknown = results.count { it.status == DepStatus.UNKNOWN }
        println("${results.size} dependencies scanned. $red red, $yellow yellow, $green green, $unknown unknown.")
    }

    private fun monthsAgo(
        date: Instant,
        now: Instant,
    ): Int = (ChronoUnit.DAYS.between(date, now) / DAYS_PER_MONTH).toInt()
}
