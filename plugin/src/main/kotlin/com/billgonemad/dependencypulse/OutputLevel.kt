package com.billgonemad.dependencypulse

import org.gradle.api.GradleException

internal enum class OutputLevel { SUMMARY_ONLY, DEFAULT, VERBOSE }

internal fun resolveOutputLevel(
    cliSummaryOnly: Boolean,
    cliShowGreen: Boolean,
    extSummaryOnly: Boolean,
    extShowGreen: Boolean,
): OutputLevel {
    if (cliSummaryOnly && cliShowGreen) {
        throw GradleException("--summary-only and --show-green are mutually exclusive")
    }
    // Validated unconditionally — even when a CLI flag wins the resolution below, an
    // internally-contradictory extension config is still a real misconfiguration that
    // should fail the build, not just when no CLI flag happens to be present.
    if (extSummaryOnly && extShowGreen) {
        throw GradleException(
            "dependencyPulse.summaryOnly and dependencyPulse.showGreen are mutually exclusive",
        )
    }
    val (summaryOnly, showGreen) =
        if (cliSummaryOnly || cliShowGreen) {
            cliSummaryOnly to cliShowGreen
        } else {
            extSummaryOnly to extShowGreen
        }
    return when {
        summaryOnly -> OutputLevel.SUMMARY_ONLY
        showGreen -> OutputLevel.VERBOSE
        else -> OutputLevel.DEFAULT
    }
}
