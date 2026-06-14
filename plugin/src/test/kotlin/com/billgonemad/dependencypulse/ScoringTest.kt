package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    private val now: Instant = Instant.now()

    @Test fun `null signals returns RED`() {
        assertEquals(DepStatus.RED, score(null, 12, 24))
    }

    @Test fun `0 days old returns GREEN`() {
        val signals = MavenSignals("1.0", now)
        assertEquals(DepStatus.GREEN, score(signals, 12, 24))
    }

    @Test fun `330 days old (11 months) returns GREEN`() {
        val signals = MavenSignals("1.0", now.minus(330, ChronoUnit.DAYS))
        assertEquals(DepStatus.GREEN, score(signals, 12, 24))
    }

    @Test fun `360 days old (12 months) returns YELLOW`() {
        val signals = MavenSignals("1.0", now.minus(360, ChronoUnit.DAYS))
        assertEquals(DepStatus.YELLOW, score(signals, 12, 24))
    }

    @Test fun `390 days old (13 months) returns YELLOW`() {
        val signals = MavenSignals("1.0", now.minus(390, ChronoUnit.DAYS))
        assertEquals(DepStatus.YELLOW, score(signals, 12, 24))
    }

    @Test fun `690 days old (23 months) returns YELLOW`() {
        val signals = MavenSignals("1.0", now.minus(690, ChronoUnit.DAYS))
        assertEquals(DepStatus.YELLOW, score(signals, 12, 24))
    }

    @Test fun `720 days old (24 months) returns RED`() {
        val signals = MavenSignals("1.0", now.minus(720, ChronoUnit.DAYS))
        assertEquals(DepStatus.RED, score(signals, 12, 24))
    }

    @Test fun `750 days old (25 months) returns RED`() {
        val signals = MavenSignals("1.0", now.minus(750, ChronoUnit.DAYS))
        assertEquals(DepStatus.RED, score(signals, 12, 24))
    }
}
