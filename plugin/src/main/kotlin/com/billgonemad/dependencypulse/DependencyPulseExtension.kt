package com.billgonemad.dependencypulse

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class DependencyPulseExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        abstract val failOnRed: Property<Boolean>
        abstract val failOnError: Property<Boolean>
        abstract val ignoreConfigurations: ListProperty<String>
        abstract val githubToken: Property<String>
        val thresholds: Thresholds = objects.newInstance(Thresholds::class.java)

        fun thresholds(action: Action<Thresholds>) {
            action.execute(thresholds)
        }

        abstract class Thresholds {
            abstract val yellowAfterMonths: Property<Int>
            abstract val redAfterMonths: Property<Int>

            companion object {
                const val DEFAULT_YELLOW_AFTER_MONTHS = 12
                const val DEFAULT_RED_AFTER_MONTHS = 24
            }
        }
    }
