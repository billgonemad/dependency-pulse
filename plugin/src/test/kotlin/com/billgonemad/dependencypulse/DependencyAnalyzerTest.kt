package com.billgonemad.dependencypulse

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DependencyAnalyzerTest {
    private val now = Instant.now()
    private val greenSignals = MavenSignals("1.0", now)

    private fun stubClient(signals: MavenSignals?): MavenCentralClient =
        object : MavenCentralClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
            ) = signals
        }

    private fun throwingClient(): MavenCentralClient =
        object : MavenCentralClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
            ): MavenSignals? = error("simulated network failure")
        }

    private fun analyzerWith(
        signals: MavenSignals?,
        coords: Set<Coords>,
    ): DependencyAnalyzer {
        val resolver = { _: Project, _: List<String> -> coords }
        return DependencyAnalyzer(stubClient(signals), resolver)
    }

    @Test fun `deduplicates coordinates across configurations`() {
        val coords =
            setOf(
                Coords("org.example", "foo", "1.0"),
            )
        val analyzer = analyzerWith(greenSignals, coords)

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(1, results.size)
        assertEquals("org.example", results[0].group)
    }

    @Test fun `passes ignoreConfigurations to resolver`() {
        var capturedIgnore: List<String>? = null
        val resolver = { _: Project, ignore: List<String> ->
            capturedIgnore = ignore
            emptySet<Coords>()
        }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), resolver)

        analyzer.analyze(ProjectBuilder.builder().build(), listOf("testImplementation"), 12, 24)

        assertEquals(listOf("testImplementation"), capturedIgnore)
    }

    @Test fun `returns RED when artifact not found on Central`() {
        val analyzer = analyzerWith(null, setOf(Coords("com.example", "gone", "1.0")))

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(DepStatus.RED, results[0].status)
    }

    @Test fun `returns UNKNOWN and sets errorMessage when client throws`() {
        val resolver = { _: Project, _: List<String> ->
            setOf(Coords("org.example", "bad", "1.0"))
        }
        val analyzer = DependencyAnalyzer(throwingClient(), resolver)

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertNotNull(results[0].errorMessage)
    }

    @Test fun `returns GREEN for recent artifact`() {
        val analyzer = analyzerWith(greenSignals, setOf(Coords("org.example", "fresh", "1.0")))

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(DepStatus.GREEN, results[0].status)
    }
}
