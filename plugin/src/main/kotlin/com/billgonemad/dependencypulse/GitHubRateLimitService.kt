package com.billgonemad.dependencypulse

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

abstract class GitHubRateLimitService :
    BuildService<BuildServiceParameters.None>,
    RateLimitState {
    private val limitedUntilRef = AtomicReference<Instant?>(null)

    override var limitedUntil: Instant?
        get() = limitedUntilRef.get()
        set(value) {
            limitedUntilRef.set(value)
        }
}
