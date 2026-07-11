package com.billgonemad.dependencypulse

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyInfoTest {
    @Test
    fun `DependencyInfo holds all fields`() {
        val maven = MavenSignals("2.0", Instant.EPOCH)
        val github = GitHubSignals.Found(Instant.EPOCH, false)
        val info =
            DependencyInfo(
                group = "com.example",
                artifact = "lib",
                currentVersion = "1.0",
                mavenSignals = maven,
                githubSignals = github,
                status = DepStatus.GREEN,
                errorMessage = null,
            )
        assertEquals("com.example", info.group)
        assertEquals("lib", info.artifact)
        assertEquals("1.0", info.currentVersion)
        assertEquals(maven, info.mavenSignals)
        assertEquals(github, info.githubSignals)
        assertFalse(info.javaxBlocker)
        assertEquals(DepStatus.GREEN, info.status)
        assertEquals(null, info.errorMessage)
    }

    @Test
    fun `GitHubSignals Found archived flag is stored`() {
        val signals = GitHubSignals.Found(Instant.EPOCH, isArchived = true)
        assertEquals(Instant.EPOCH, signals.lastCommitDate)
        assertEquals(true, signals.isArchived)
    }

    @Test
    fun `GitHubSignals Found allows a null lastCommitDate`() {
        val signals = GitHubSignals.Found(lastCommitDate = null, isArchived = true)
        assertEquals(null, signals.lastCommitDate)
        assertEquals(true, signals.isArchived)
    }

    private fun dependencyInfo(
        knownStable: Boolean,
        mavenSignals: MavenSignals?,
        githubSignals: GitHubSignals,
    ): DependencyInfo =
        DependencyInfo(
            group = "com.example",
            artifact = "lib",
            currentVersion = "1.0",
            mavenSignals = mavenSignals,
            githubSignals = githubSignals,
            status = DepStatus.GREEN,
            errorMessage = null,
            knownStable = knownStable,
        )

    @Test
    fun `isKnownStableWithSignals is true when known stable with maven signals and non-archived github`() {
        val info =
            dependencyInfo(
                knownStable = true,
                mavenSignals = MavenSignals("1.0", Instant.EPOCH),
                githubSignals = GitHubSignals.NoRepo,
            )
        assertTrue(info.isKnownStableWithSignals())
    }

    @Test
    fun `isKnownStableWithSignals is false when maven signals are null`() {
        val info =
            dependencyInfo(
                knownStable = true,
                mavenSignals = null,
                githubSignals = GitHubSignals.NoRepo,
            )
        assertFalse(info.isKnownStableWithSignals())
    }

    @Test
    fun `isKnownStableWithSignals is false when not known stable`() {
        val info =
            dependencyInfo(
                knownStable = false,
                mavenSignals = MavenSignals("1.0", Instant.EPOCH),
                githubSignals = GitHubSignals.NoRepo,
            )
        assertFalse(info.isKnownStableWithSignals())
    }

    @Test
    fun `isKnownStableWithSignals is false when github repo is archived`() {
        val info =
            dependencyInfo(
                knownStable = true,
                mavenSignals = MavenSignals("1.0", Instant.EPOCH),
                githubSignals = GitHubSignals.Found(lastCommitDate = null, isArchived = true),
            )
        assertFalse(info.isKnownStableWithSignals())
    }

    @Test
    fun `isKnownStableWithSignals is true when github repo found but not archived`() {
        val info =
            dependencyInfo(
                knownStable = true,
                mavenSignals = MavenSignals("1.0", Instant.EPOCH),
                githubSignals = GitHubSignals.Found(lastCommitDate = null, isArchived = false),
            )
        assertTrue(info.isKnownStableWithSignals())
    }
}
