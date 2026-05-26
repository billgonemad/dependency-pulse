package com.billgonemad.dependencypulse

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class DependencyPulseTask : DefaultTask() {
    @get:Input
    abstract val failOnRed: Property<Boolean>

    @get:Input
    abstract val failOnError: Property<Boolean>

    @get:Input
    abstract val ignoreConfigurations: ListProperty<String>

    @get:Input
    abstract val yellowAfterMonths: Property<Int>

    @get:Input
    abstract val redAfterMonths: Property<Int>

    @get:Input
    abstract val mavenCentralBaseUrl: Property<String>

    @TaskAction
    fun run() {
        // wired in Task 7
    }
}
