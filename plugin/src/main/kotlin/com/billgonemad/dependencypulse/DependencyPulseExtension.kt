package com.billgonemad.dependencypulse

import org.gradle.api.Action

open class DependencyPulseExtension {
    var failOnRed: Boolean = false
    var failOnError: Boolean = false
    var ignoreConfigurations: List<String> =
        listOf(
            "testImplementation",
            "testRuntimeOnly",
            "testCompileClasspath",
            "testRuntimeClasspath",
        )
    var githubToken: String? = null
    val thresholds: Thresholds = Thresholds()

    fun thresholds(action: Action<Thresholds>) {
        action.execute(thresholds)
    }

    class Thresholds {
        var yellowAfterMonths: Int = DEFAULT_YELLOW_AFTER_MONTHS
        var redAfterMonths: Int = DEFAULT_RED_AFTER_MONTHS

        companion object {
            const val DEFAULT_YELLOW_AFTER_MONTHS = 12
            const val DEFAULT_RED_AFTER_MONTHS = 24
        }
    }
}
