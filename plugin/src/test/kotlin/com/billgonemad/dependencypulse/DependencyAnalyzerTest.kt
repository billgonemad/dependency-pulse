package com.billgonemad.dependencypulse

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

    // A single, realistic-looking repo URL for tests that don't care about repo-walk behavior
    // itself — DependencyAnalyzer no longer knows or cares what the plugin's real default
    // pomBaseUrl is (that's ProjectInputs.kt/DependencyPulsePlugin's concern now), this is just
    // a representative placeholder, not a check against the real default.
    private val singleRepoUrls = listOf("https://repo1.maven.org/maven2")

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
            override fun lookupGitHubRepo(
                group: String,
                artifact: String,
                version: String,
                baseUrl: String,
            ) = PomFetch.Success(repo)
        }

    private fun throwingPomClient(): PomClient =
        object : PomClient() {
            override fun lookupGitHubRepo(
                group: String,
                artifact: String,
                version: String,
                baseUrl: String,
            ): PomFetch = error("simulated PomClient bug")
        }

    private fun stubGithubClient(signals: GitHubSignals = GitHubSignals.FetchFailed): GitHubClient =
        object : GitHubClient() {
            override fun fetchSignals(ownerRepo: String) = signals
        }

    private fun analyzerWith(
        signals: MavenSignals?,
        pomClient: PomClient = stubPomClient(),
        githubClient: GitHubClient = stubGithubClient(),
    ): DependencyAnalyzer = DependencyAnalyzer(stubClient(signals), pomClient, githubClient)

    @Test fun `returns RED when artifact not found on Central`() {
        val analyzer = analyzerWith(null)

        val results =
            analyzer.analyze(setOf(Coords("com.example", "gone", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(DepStatus.RED, results[0].status)
    }

    @Test fun `returns UNKNOWN and sets errorMessage when client throws`() {
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(setOf(Coords("org.example", "bad", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertNotNull(results[0].errorMessage)
    }

    @Test fun `returns GREEN for recent artifact`() {
        val analyzer = analyzerWith(greenSignals)

        val results =
            analyzer.analyze(setOf(Coords("org.example", "fresh", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(DepStatus.GREEN, results[0].status)
    }

    @Test fun `populates githubSignals when the dependency has a resolvable GitHub repo`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = false)
        val analyzer =
            analyzerWith(
                greenSignals,
                pomClient = stubPomClient("org/hosted"),
                githubClient = stubGithubClient(githubSignals),
            )

        val results =
            analyzer.analyze(setOf(Coords("org.example", "hosted", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(githubSignals, results[0].githubSignals)
    }

    @Test fun `leaves githubSignals as NoRepo when no GitHub repo can be resolved from the POM`() {
        val analyzer = analyzerWith(greenSignals, pomClient = stubPomClient(null))

        val results =
            analyzer.analyze(setOf(Coords("org.example", "unhosted", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(GitHubSignals.NoRepo, results[0].githubSignals)
    }

    @Test fun `folds in an archived GitHub repo when the Maven lookup is unresolvable`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = true)
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient("org/bad"), stubGithubClient(githubSignals))

        val results =
            analyzer.analyze(setOf(Coords("org.example", "bad", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(githubSignals, results[0].githubSignals)
        assertEquals(DepStatus.RED, results[0].status)
    }

    @Test fun `sets knownStable when the coordinate matches a configured group prefix`() {
        val analyzer = analyzerWith(greenSignals)

        val results =
            analyzer.analyze(
                setOf(Coords("jakarta.annotation", "jakarta.annotation-api", "3.0.0")),
                singleRepoUrls,
                12,
                24,
                listOf("jakarta."),
            )

        assertTrue(results[0].knownStable)
    }

    @Test fun `leaves knownStable false when no configured entry matches`() {
        val analyzer = analyzerWith(greenSignals)

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                singleRepoUrls,
                12,
                24,
                listOf("jakarta.", "javax."),
            )

        assertFalse(results[0].knownStable)
    }

    @Test fun `sets knownStable on both the success path and the exception path`() {
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("jakarta.annotation", "jakarta.annotation-api", "3.0.0")),
                singleRepoUrls,
                12,
                24,
                listOf("jakarta."),
            )

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertTrue(results[0].knownStable)
    }

    @Test fun `sets githubSignals to FetchFailed when PomClient throws unexpectedly`() {
        val analyzer = analyzerWith(greenSignals, pomClient = throwingPomClient())

        val results =
            analyzer.analyze(setOf(Coords("org.example", "risky", "1.0")), singleRepoUrls, 12, 24, emptyList())

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
        val analyzer = DependencyAnalyzer(slowClient, stubPomClient(), stubGithubClient())

        val elapsedMs =
            measureTimeMillis {
                analyzer.analyze(coords, singleRepoUrls, 12, 24, emptyList())
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
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

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
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(DepStatus.GREEN, results[0].status)
        assertEquals(2, calledBaseUrls.size)
    }

    @Test fun `returns RED when every declared repo returns a clean 404`() {
        val analyzer = DependencyAnalyzer(stubClient(null), stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("com.example", "gone", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

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
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

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
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertNotNull(results[0].errorMessage)
    }

    @Test fun `keeps the fresher earlier result when a later declared repo returns a staler one`() {
        // Both ages land in YELLOW under the test's 12/24-month thresholds, so the loop must
        // exhaust the whole repo list rather than stopping early on a GREEN result.
        val newerSignals = MavenSignals("1.1", now.minusSeconds(60L * 60 * 24 * 400))
        val olderSignals = MavenSignals("1.0", now.minusSeconds(60L * 60 * 24 * 500))
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ): MavenSignals? = if (baseUrl.endsWith("second")) olderSignals else newerSignals
            }
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals("1.1", results[0].mavenSignals?.latestVersion)
    }

    @Test fun `keeps the first repo's error when a later repo also throws`() {
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ): MavenSignals? =
                    if (baseUrl.endsWith("second")) {
                        error("second repo failure")
                    } else {
                        error("first repo failure")
                    }
            }
        val analyzer = DependencyAnalyzer(client, stubPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "foo", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(DepStatus.UNKNOWN, results[0].status)
        assertEquals("first repo failure", results[0].errorMessage)
    }

    @Test fun `keeps walking the github repo lookup past a repo whose POM is not found`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = false)
        val calledBaseUrls = mutableListOf<String>()
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch {
                    calledBaseUrls.add(baseUrl)
                    return if (baseUrl.endsWith("second")) PomFetch.Success("org/hosted") else PomFetch.NotFound
                }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient(githubSignals))

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "hosted", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(githubSignals, results[0].githubSignals)
        assertEquals(2, calledBaseUrls.size)
    }

    @Test fun `stops the github repo lookup at the first repo whose POM resolves, even without an scm link`() {
        val calledBaseUrls = mutableListOf<String>()
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch {
                    calledBaseUrls.add(baseUrl)
                    return PomFetch.Success(null)
                }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "bare", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(GitHubSignals.NoRepo, results[0].githubSignals)
        assertEquals(1, calledBaseUrls.size)
    }

    @Test fun `resolves githubSignals to NoRepo when every declared repo 404s for the POM`() {
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch = PomFetch.NotFound
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "missing", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(GitHubSignals.NoRepo, results[0].githubSignals)
    }

    @Test fun `stays UNKNOWN when the Maven lookup is unresolvable and GitHub has no worse signal`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = false)
        val analyzer = DependencyAnalyzer(throwingClient(), stubPomClient("org/bad"), stubGithubClient(githubSignals))

        val results =
            analyzer.analyze(setOf(Coords("org.example", "bad", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(DepStatus.UNKNOWN, results[0].status)
    }

    @Test fun `climbs to the parent POM when the child has no scm link`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = false)
        val childCoord = Coords("org.example", "child", "1.0")
        val parentCoord = Coords("org.example", "child-parent", "2.0")
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch =
                    if (Coords(group, artifact, version) == childCoord) {
                        PomFetch.Success(githubRepo = null, parentCoords = parentCoord)
                    } else {
                        PomFetch.Success(githubRepo = "org/hosted", parentCoords = null)
                    }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient(githubSignals))

        val results = analyzer.analyze(setOf(childCoord), singleRepoUrls, 12, 24, emptyList())

        assertEquals(githubSignals, results[0].githubSignals)
    }

    @Test fun `climbs through two parent hops to find the scm link at the grandparent`() {
        val githubSignals = GitHubSignals.Found(now, isArchived = false)
        val childCoord = Coords("org.example", "child", "1.0")
        val parentCoord = Coords("org.example", "mid-parent", "1.0")
        val grandparentCoord = Coords("org.example", "top-parent", "1.0")
        val callLog = mutableListOf<Coords>()
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch {
                    val coord = Coords(group, artifact, version)
                    callLog.add(coord)
                    return when (coord) {
                        childCoord -> PomFetch.Success(githubRepo = null, parentCoords = parentCoord)
                        parentCoord -> PomFetch.Success(githubRepo = null, parentCoords = grandparentCoord)
                        else -> PomFetch.Success(githubRepo = "org/hosted", parentCoords = null)
                    }
                }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient(githubSignals))

        val results = analyzer.analyze(setOf(childCoord), singleRepoUrls, 12, 24, emptyList())

        assertEquals(githubSignals, results[0].githubSignals)
        assertEquals(listOf(childCoord, parentCoord, grandparentCoord), callLog)
    }

    @Test fun `stops climbing after the depth cap and resolves to NoRepo`() {
        var callCount = 0
        val childCoord = Coords("org.example", "child", "1.0")
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch {
                    callCount++
                    // Every level has a parent but none carries an scm link, so the chain never
                    // terminates naturally and the walk must rely on the depth cap to stop.
                    return PomFetch.Success(
                        githubRepo = null,
                        parentCoords = Coords(group, "$artifact-parent", version),
                    )
                }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient())

        val results = analyzer.analyze(setOf(childCoord), singleRepoUrls, 12, 24, emptyList())

        assertEquals(GitHubSignals.NoRepo, results[0].githubSignals)
        assertEquals(5, callCount)
    }

    @Test fun `treats a parent GAV not found on any declared repo the same as a chain with no further parent`() {
        val childCoord = Coords("org.example", "child", "1.0")
        val missingParent = Coords("org.example", "ghost-parent", "1.0")
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch =
                    if (Coords(group, artifact, version) == childCoord) {
                        PomFetch.Success(githubRepo = null, parentCoords = missingParent)
                    } else {
                        PomFetch.NotFound
                    }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient())

        val results = analyzer.analyze(setOf(childCoord), singleRepoUrls, 12, 24, emptyList())

        assertEquals(GitHubSignals.NoRepo, results[0].githubSignals)
    }
}
