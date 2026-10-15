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

    private fun info(
        artifact: String = "lib",
        status: DepStatus = DepStatus.RED,
        knownStable: Boolean = false,
    ) = DependencyInfo(
        group = "com.example",
        artifact = artifact,
        currentVersion = "1.0",
        mavenSignals = MavenSignals("1.0", Instant.EPOCH),
        githubSignals = GitHubSignals.NoRepo,
        status = status,
        errorMessage = null,
        knownStable = knownStable,
    )

    @Test
    fun `coordinateSummary lists every coordinate up to the limit`() {
        val deps = listOf(info(artifact = "a"), info(artifact = "b"))
        assertEquals("com.example:a:1.0, com.example:b:1.0", deps.coordinateSummary())
    }

    @Test
    fun `coordinateSummary caps at ten coordinates and counts the rest`() {
        val deps = (1..12).map { info(artifact = "lib$it") }
        val summary = deps.coordinateSummary()
        assertTrue(summary.startsWith("com.example:lib1:1.0, "), summary)
        assertTrue(summary.contains("com.example:lib10:1.0"), summary)
        assertFalse(summary.contains("com.example:lib11:1.0"), summary)
        assertTrue(summary.endsWith(" and 2 more"), summary)
    }

    @Test
    fun `isUnexemptedRed is true for a plain RED dependency`() {
        assertTrue(info(status = DepStatus.RED).isUnexemptedRed())
    }

    @Test
    fun `isUnexemptedRed is false for a known-stable RED dependency with Maven signals`() {
        assertFalse(info(status = DepStatus.RED, knownStable = true).isUnexemptedRed())
    }

    @Test
    fun `isUnexemptedRed is false for non-RED statuses`() {
        assertFalse(info(status = DepStatus.YELLOW).isUnexemptedRed())
    }
}
