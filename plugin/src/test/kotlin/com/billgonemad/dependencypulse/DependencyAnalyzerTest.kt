package com.billgonemad.dependencypulse

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyAnalyzerTest {
    private val now = Instant.now()
    private val greenSignals = MavenSignals("1.0", now)

    private fun stubClient(signals: MavenSignals?): MavenCentralClient =
        object : MavenCentralClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
                currentVersion: String,
            ) = signals
        }

    private fun throwingClient(): MavenCentralClient =
        object : MavenCentralClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
                currentVersion: String,
            ): MavenSignals? = error("simulated network failure")
        }

    private fun stubPomClient(repo: String? = null): PomClient =
        object : PomClient() {
            override fun fetchGitHubRepo(
                group: String,
                artifact: String,
                version: String,
            ) = repo
        }

    private fun throwingPomClient(): PomClient =
        object : PomClient() {
            override fun fetchGitHubRepo(
                group: String,
                artifact: String,
                version: String,
            ): String? = error("simulated PomClient bug")
        }

    private fun stubGithubClient(signals: GitHubSignals = GitHubSignals.FetchFailed): GitHubClient =
        object : GitHubClient() {
            override fun fetchSignals(ownerRepo: String) = signals
        }

    private fun analyzerWith(
        signals: MavenSignals?,
        coords: Set<Coords>,
        pomClient: PomClient = stubPomClient(),
        githubClient: GitHubClient = stubGithubClient(),
    ): DependencyAnalyzer {
        val resolver = { _: Project, _: List<String> -> coords }
        return DependencyAnalyzer(stubClient(signals), pomClient, githubClient, resolver)
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
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), stubPomClient(), stubGithubClient(), resolver)

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
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient(), stubGithubClient(), resolver)

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertNotNull(results[0].errorMessage)
    }

    @Test fun `returns GREEN for recent artifact`() {
        val analyzer = analyzerWith(greenSignals, setOf(Coords("org.example", "fresh", "1.0")))

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(DepStatus.GREEN, results[0].status)
    }

    @Test fun `populates githubSignals when the dependency has a resolvable GitHub repo`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = false)
        val analyzer =
            analyzerWith(
                greenSignals,
                setOf(Coords("org.example", "hosted", "1.0")),
                pomClient = stubPomClient("org/hosted"),
                githubClient = stubGithubClient(githubSignals),
            )

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(githubSignals, results[0].githubSignals)
    }

    @Test fun `leaves githubSignals as NoRepo when no GitHub repo can be resolved from the POM`() {
        val analyzer =
            analyzerWith(
                greenSignals,
                setOf(Coords("org.example", "unhosted", "1.0")),
                pomClient = stubPomClient(null),
            )

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(GitHubSignals.NoRepo, results[0].githubSignals)
    }

    @Test fun `still populates githubSignals when the Maven Central lookup throws`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = true)
        val resolver = { _: Project, _: List<String> ->
            setOf(Coords("org.example", "bad", "1.0"))
        }
        val analyzer =
            DependencyAnalyzer(
                throwingClient(),
                stubPomClient("org/bad"),
                stubGithubClient(githubSignals),
                resolver,
            )

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertEquals(githubSignals, results[0].githubSignals)
    }

    @Test fun `sets githubSignals to FetchFailed when PomClient throws unexpectedly`() {
        val analyzer =
            analyzerWith(
                greenSignals,
                setOf(Coords("org.example", "risky", "1.0")),
                pomClient = throwingPomClient(),
            )

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)

        assertEquals(GitHubSignals.FetchFailed, results[0].githubSignals)
        assertEquals(DepStatus.GREEN, results[0].status)
    }

    @Test fun `resolves multiple dependencies concurrently rather than strictly sequentially`() {
        val delayMs = 200L
        val slowClient =
            object : MavenCentralClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                ): MavenSignals? {
                    Thread.sleep(delayMs)
                    return greenSignals
                }
            }
        val coords = (1..4).map { Coords("org.example", "dep$it", "1.0") }.toSet()
        val resolver = { _: Project, _: List<String> -> coords }
        val analyzer = DependencyAnalyzer(slowClient, stubPomClient(), stubGithubClient(), resolver)

        val elapsedMs =
            measureTimeMillis {
                analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24)
            }

        assertTrue(elapsedMs < delayMs * coords.size)
    }
}
