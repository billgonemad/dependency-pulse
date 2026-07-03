package com.billgonemad.dependencypulse

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.atomic.AtomicBoolean

abstract class GitHubRateLimitService :
    BuildService<BuildServiceParameters.None>,
    RateLimitState {
    private val limitedFlag = AtomicBoolean(false)

    override var limited: Boolean
        get() = limitedFlag.get()
        set(value) {
            limitedFlag.set(value)
        }
}
