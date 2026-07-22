package com.billgonemad.dependencypulse

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.time.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyAnalyzerTest {
    private val now = Instant.now()
    private val greenSignals = MavenSignals("1.0", now)

    private fun stubClient(signals: MavenSignals?): MavenMetadataClient =
        object : MavenMetadataClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
                currentVersion: String,
                baseUrl: String,
            ) = signals
        }

    private fun throwingClient(): MavenMetadataClient =
        object : MavenMetadataClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
                currentVersion: String,
                baseUrl: String,
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

    @Test fun `repo list starts with pomBaseUrl followed by declared http repos in order`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = java.net.URI("https://repo.example.com/first") }
        project.repositories.maven { it.url = java.net.URI("https://repo.example.com/second") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(
            listOf(
                "https://repo1.maven.org/maven2",
                "https://repo.example.com/first",
                "https://repo.example.com/second",
            ),
            urls,
        )
    }

    @Test fun `dedupes a declared repo matching pomBaseUrl after trailing-slash trim`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = java.net.URI("https://repo1.maven.org/maven2/") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2"), urls)
    }

    @Test fun `excludes mavenLocal since it resolves to a file URI MavenMetadataClient cannot query`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.mavenLocal()
        project.repositories.maven { it.url = java.net.URI("https://repo.example.com") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2", "https://repo.example.com"), urls)
    }

    @Test fun `excludes non-Maven repository types`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.ivy { it.url = java.net.URI("https://ivy.example.com") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2"), urls)
    }

    @Test fun `returns only pomBaseUrl when no repositories are declared`() {
        val project = ProjectBuilder.builder().build()

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2"), urls)
    }

    private fun projectWithRepos(vararg urls: String): Project {
        val project = ProjectBuilder.builder().build()
        urls.forEach { url -> project.repositories.maven { it.url = java.net.URI(url) } }
        return project
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

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

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

        analyzer.analyze(ProjectBuilder.builder().build(), listOf("testImplementation"), 12, 24, emptyList())

        assertEquals(listOf("testImplementation"), capturedIgnore)
    }

    @Test fun `returns RED when artifact not found on Central`() {
        val analyzer = analyzerWith(null, setOf(Coords("com.example", "gone", "1.0")))

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.RED, results[0].status)
    }

    @Test fun `returns UNKNOWN and sets errorMessage when client throws`() {
        val resolver = { _: Project, _: List<String> ->
            setOf(Coords("org.example", "bad", "1.0"))
        }
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient(), stubGithubClient(), resolver)

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertNotNull(results[0].errorMessage)
    }

    @Test fun `returns GREEN for recent artifact`() {
        val analyzer = analyzerWith(greenSignals, setOf(Coords("org.example", "fresh", "1.0")))

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

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

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

        assertEquals(githubSignals, results[0].githubSignals)
    }

    @Test fun `leaves githubSignals as NoRepo when no GitHub repo can be resolved from the POM`() {
        val analyzer =
            analyzerWith(
                greenSignals,
                setOf(Coords("org.example", "unhosted", "1.0")),
                pomClient = stubPomClient(null),
            )

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

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

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertEquals(githubSignals, results[0].githubSignals)
    }

    @Test fun `sets knownStable when the coordinate matches a configured group prefix`() {
        val analyzer =
            analyzerWith(greenSignals, setOf(Coords("jakarta.annotation", "jakarta.annotation-api", "3.0.0")))

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, listOf("jakarta."))

        assertTrue(results[0].knownStable)
    }

    @Test fun `leaves knownStable false when no configured entry matches`() {
        val analyzer = analyzerWith(greenSignals, setOf(Coords("org.example", "foo", "1.0")))

        val results =
            analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, listOf("jakarta.", "javax."))

        assertFalse(results[0].knownStable)
    }

    @Test fun `sets knownStable on both the success path and the exception path`() {
        val resolver = { _: Project, _: List<String> ->
            setOf(Coords("jakarta.annotation", "jakarta.annotation-api", "3.0.0"))
        }
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient(), stubGithubClient(), resolver)

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, listOf("jakarta."))

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertTrue(results[0].knownStable)
    }

    @Test fun `sets githubSignals to FetchFailed when PomClient throws unexpectedly`() {
        val analyzer =
            analyzerWith(
                greenSignals,
                setOf(Coords("org.example", "risky", "1.0")),
                pomClient = throwingPomClient(),
            )

        val results = analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())

        assertEquals(GitHubSignals.FetchFailed, results[0].githubSignals)
        assertEquals(DepStatus.GREEN, results[0].status)
    }

    @Test fun `resolves multiple dependencies concurrently rather than strictly sequentially`() {
        val delayMs = 200L
        val slowClient =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
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
                analyzer.analyze(ProjectBuilder.builder().build(), emptyList(), 12, 24, emptyList())
            }

        assertTrue(elapsedMs < delayMs * coords.size)
    }

    @Test fun `stops at the first repo whose result is Maven-GREEN`() {
        var callCount = 0
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ): MavenSignals? {
                    callCount++
                    return greenSignals
                }
            }
        val resolver = { _: Project, _: List<String> -> setOf(Coords("org.example", "foo", "1.0")) }
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient(), resolver)
        val project = projectWithRepos("https://repo.example.com/second")

        val results = analyzer.analyze(project, emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.GREEN, results[0].status)
        assertEquals(1, callCount)
    }

    @Test fun `falls through to a second declared repo when the first is non-GREEN`() {
        val staleSignals = MavenSignals("0.1", now.minusSeconds(60L * 60 * 24 * 30 * 36))
        val calledBaseUrls = mutableListOf<String>()
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ): MavenSignals? {
                    calledBaseUrls.add(baseUrl)
                    return if (baseUrl.endsWith("second")) greenSignals else staleSignals
                }
            }
        val resolver = { _: Project, _: List<String> -> setOf(Coords("org.example", "foo", "1.0")) }
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient(), resolver)
        val project = projectWithRepos("https://repo.example.com/second")

        val results = analyzer.analyze(project, emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.GREEN, results[0].status)
        assertEquals(2, calledBaseUrls.size)
    }

    @Test fun `returns RED when every declared repo returns a clean 404`() {
        val resolver = { _: Project, _: List<String> -> setOf(Coords("com.example", "gone", "1.0")) }
        val analyzer = DependencyAnalyzer(stubClient(null), stubPomClient(), stubGithubClient(), resolver)
        val project = projectWithRepos("https://repo.example.com/second")

        val results = analyzer.analyze(project, emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.RED, results[0].status)
    }

    @Test fun `falls back to the freshest real result when no repo is GREEN`() {
        // Both ages land in YELLOW under the test's 12/24-month thresholds (30-day months, per
        // DependencyInfo.kt's DAYS_PER_MONTH): 500 days ~= 16.7 months, 400 days ~= 13.3 months.
        // Neither reaches GREEN (< 12 months), so the loop must exhaust the whole repo list.
        val olderSignals = MavenSignals("1.0", now.minusSeconds(60L * 60 * 24 * 500))
        val newerSignals = MavenSignals("1.1", now.minusSeconds(60L * 60 * 24 * 400))
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ): MavenSignals? = if (baseUrl.endsWith("second")) newerSignals else olderSignals
            }
        val resolver = { _: Project, _: List<String> -> setOf(Coords("org.example", "foo", "1.0")) }
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient(), resolver)
        val project = projectWithRepos("https://repo.example.com/second")

        val results = analyzer.analyze(project, emptyList(), 12, 24, emptyList())

        assertEquals("1.1", results[0].mavenSignals?.latestVersion)
    }

    @Test fun `returns UNKNOWN when one repo throws and nothing is ever found`() {
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ): MavenSignals? = if (baseUrl.endsWith("second")) null else error("simulated network failure")
            }
        val resolver = { _: Project, _: List<String> -> setOf(Coords("org.example", "foo", "1.0")) }
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient(), resolver)
        val project = projectWithRepos("https://repo.example.com/second")

        val results = analyzer.analyze(project, emptyList(), 12, 24, emptyList())

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertNotNull(results[0].errorMessage)
    }
}
