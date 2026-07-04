package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    private val now: Instant = Instant.now()

    @Test fun `null signals returns RED`() {
        assertEquals(DepStatus.RED, score(null, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `0 days old returns GREEN`() {
        val signals = MavenSignals("1.0", now)
        assertEquals(DepStatus.GREEN, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `330 days old (11 months) returns GREEN`() {
        val signals = MavenSignals("1.0", now.minus(330, ChronoUnit.DAYS))
        assertEquals(DepStatus.GREEN, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `360 days old (12 months) returns YELLOW`() {
        val signals = MavenSignals("1.0", now.minus(360, ChronoUnit.DAYS))
        assertEquals(DepStatus.YELLOW, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `390 days old (13 months) returns YELLOW`() {
        val signals = MavenSignals("1.0", now.minus(390, ChronoUnit.DAYS))
        assertEquals(DepStatus.YELLOW, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `690 days old (23 months) returns YELLOW`() {
        val signals = MavenSignals("1.0", now.minus(690, ChronoUnit.DAYS))
        assertEquals(DepStatus.YELLOW, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `720 days old (24 months) returns RED`() {
        val signals = MavenSignals("1.0", now.minus(720, ChronoUnit.DAYS))
        assertEquals(DepStatus.RED, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `750 days old (25 months) returns RED`() {
        val signals = MavenSignals("1.0", now.minus(750, ChronoUnit.DAYS))
        assertEquals(DepStatus.RED, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `archived GitHub repo forces RED even when Maven is fresh`() {
        val signals = MavenSignals("1.0", now)
        val github = GitHubSignals.Found(lastCommitDate = now, isArchived = true)
        assertEquals(DepStatus.RED, score(signals, github, 12, 24))
    }

    @Test fun `archived GitHub repo forces RED even with no Maven signals`() {
        val github = GitHubSignals.Found(lastCommitDate = now, isArchived = true)
        assertEquals(DepStatus.RED, score(null, github, 12, 24))
    }

    @Test fun `stale GitHub commits downgrade a fresh Maven release to YELLOW`() {
        val signals = MavenSignals("1.0", now)
        val github = GitHubSignals.Found(lastCommitDate = now.minus(390, ChronoUnit.DAYS), isArchived = false)
        assertEquals(DepStatus.YELLOW, score(signals, github, 12, 24))
    }

    @Test fun `very stale GitHub commits downgrade a fresh Maven release to RED`() {
        val signals = MavenSignals("1.0", now)
        val github = GitHubSignals.Found(lastCommitDate = now.minus(750, ChronoUnit.DAYS), isArchived = false)
        assertEquals(DepStatus.RED, score(signals, github, 12, 24))
    }

    @Test fun `fresh GitHub commits do not downgrade a fresh Maven release`() {
        val signals = MavenSignals("1.0", now)
        val github = GitHubSignals.Found(lastCommitDate = now, isArchived = false)
        assertEquals(DepStatus.GREEN, score(signals, github, 12, 24))
    }

    @Test fun `NoRepo GitHub signal does not change Maven-derived status`() {
        val signals = MavenSignals("1.0", now)
        assertEquals(DepStatus.GREEN, score(signals, GitHubSignals.NoRepo, 12, 24))
    }

    @Test fun `RateLimited GitHub signal does not change Maven-derived status`() {
        val signals = MavenSignals("1.0", now)
        assertEquals(DepStatus.GREEN, score(signals, GitHubSignals.RateLimited, 12, 24))
    }

    @Test fun `FetchFailed GitHub signal does not change Maven-derived status`() {
        val signals = MavenSignals("1.0", now)
        assertEquals(DepStatus.GREEN, score(signals, GitHubSignals.FetchFailed, 12, 24))
    }

    @Test fun `Found with null lastCommitDate and not archived does not change Maven-derived status`() {
        val signals = MavenSignals("1.0", now)
        val github = GitHubSignals.Found(lastCommitDate = null, isArchived = false)
        assertEquals(DepStatus.GREEN, score(signals, github, 12, 24))
    }

    @Test fun `worst-of wins - a fresh GitHub commit does not improve a RED Maven status`() {
        val github = GitHubSignals.Found(lastCommitDate = now, isArchived = false)
        assertEquals(DepStatus.RED, score(null, github, 12, 24))
    }
}
