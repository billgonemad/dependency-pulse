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

    private fun stubClientWithProbe(
        signals: MavenSignals?,
        probe: (String) -> MavenSignals?,
    ): MavenMetadataClient =
        object : MavenMetadataClient() {
            override fun fetchSignals(
                group: String,
                artifact: String,
                currentVersion: String,
                baseUrl: String,
            ) = signals

            override fun probeVersion(
                group: String,
                artifact: String,
                version: String,
                baseUrl: String,
            ) = probe(version)
        }

    private fun stubGithubClientWithReleases(
        signals: GitHubSignals,
        releaseTags: List<String>,
    ): GitHubClient =
        object : GitHubClient() {
            override fun fetchSignals(ownerRepo: String) = signals

            override fun fetchRecentReleaseTags(
                ownerRepo: String,
                limit: Int,
            ) = releaseTags
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

    @Test fun `escalates to a verified GitHub-derived candidate when the maven walk is unverified`() {
        val unverified = MavenSignals("8.14.4", now.minus(180, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val verifiedCandidate = MavenSignals("9.7.1", now, verified = true)
        val client =
            stubClientWithProbe(unverified) { version -> if (version == "9.7.1") verifiedCandidate else null }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("gradle/gradle"),
                stubGithubClientWithReleases(GitHubSignals.Found(now, isArchived = false), listOf("v9.7.1")),
            )

        val results =
            analyzer.analyze(
                setOf(Coords("org.gradle", "gradle-tooling-api", "8.14.4")),
                singleRepoUrls,
                12,
                24,
                emptyList(),
            )

        assertEquals(verifiedCandidate, results[0].mavenSignals)
    }

    @Test fun `falls back to the unverified signals when no GitHub candidate resolves`() {
        val unverified = MavenSignals("8.14.4", now.minus(180, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val client = stubClientWithProbe(unverified) { null }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("gradle/gradle"),
                stubGithubClientWithReleases(GitHubSignals.Found(now, isArchived = false), listOf("v9.7.1")),
            )

        val results =
            analyzer.analyze(
                setOf(Coords("org.gradle", "gradle-tooling-api", "8.14.4")),
                singleRepoUrls,
                12,
                24,
                emptyList(),
            )

        assertEquals(unverified, results[0].mavenSignals)
    }

    @Test fun `falls back to the unverified signals when GitHub repo resolution throws during escalation`() {
        val unverified = MavenSignals("1.0.0", now.minus(400, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val analyzer = DependencyAnalyzer(stubClient(unverified), throwingPomClient(), stubGithubClient())

        val results =
            analyzer.analyze(setOf(Coords("org.example", "widget", "1.0.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(unverified, results[0].mavenSignals)
    }

    @Test fun `skips escalation entirely when no GitHub repo can be resolved`() {
        val unverified = MavenSignals("8.14.4", now.minus(180, java.time.temporal.ChronoUnit.DAYS), verified = false)
        var releaseCallCount = 0
        val githubClient =
            object : GitHubClient() {
                override fun fetchSignals(ownerRepo: String) = GitHubSignals.Found(now, isArchived = false)

                override fun fetchRecentReleaseTags(
                    ownerRepo: String,
                    limit: Int,
                ): List<String> {
                    releaseCallCount++
                    return listOf("v9.7.1")
                }
            }
        val analyzer = DependencyAnalyzer(stubClient(unverified), stubPomClient(null), githubClient)

        val results =
            analyzer.analyze(
                setOf(Coords("org.gradle", "gradle-tooling-api", "8.14.4")),
                singleRepoUrls,
                12,
                24,
                emptyList(),
            )

        assertEquals(unverified, results[0].mavenSignals)
        assertEquals(0, releaseCallCount)
    }

    @Test fun `never attempts escalation when the maven walk result is already verified`() {
        var releaseCallCount = 0
        val githubClient =
            object : GitHubClient() {
                override fun fetchSignals(ownerRepo: String) = GitHubSignals.Found(now, isArchived = false)

                override fun fetchRecentReleaseTags(
                    ownerRepo: String,
                    limit: Int,
                ): List<String> {
                    releaseCallCount++
                    return listOf("v9.7.1")
                }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), stubPomClient("gradle/gradle"), githubClient)

        val results =
            analyzer.analyze(setOf(Coords("org.example", "fresh", "1.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(greenSignals, results[0].mavenSignals)
        assertEquals(0, releaseCallCount)
    }

    @Test fun `tries normalized candidates in order and stops at the first that resolves`() {
        val unverified = MavenSignals("1.0.0", now.minus(180, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val probedVersions = mutableListOf<String>()
        val client =
            stubClientWithProbe(unverified) { version ->
                probedVersions.add(version)
                if (version == "2.0.0") MavenSignals("2.0.0", now, verified = true) else null
            }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("org/widget"),
                stubGithubClientWithReleases(GitHubSignals.Found(now, isArchived = false), listOf("v2.0.0")),
            )

        analyzer.analyze(setOf(Coords("org.example", "widget", "1.0.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(listOf("v2.0.0", "2.0.0"), probedVersions)
    }

    @Test fun `picks the freshest verified candidate across releases, not just the first that resolves`() {
        val unverified = MavenSignals("1.0.0", now.minus(400, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val client =
            stubClientWithProbe(unverified) { version ->
                when (version) {
                    "1.5.0" -> MavenSignals("1.5.0", now.minus(60, java.time.temporal.ChronoUnit.DAYS), verified = true)
                    "2.0.0" -> MavenSignals("2.0.0", now, verified = true)
                    else -> null
                }
            }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("org/widget"),
                // Deliberately lists the older release before the newer one — GitHub's list order
                // isn't a documented contract this design relies on (see spec's "Candidate source"),
                // so this proves the freshest verified candidate wins regardless of list position,
                // not whichever tag happens to resolve first.
                stubGithubClientWithReleases(
                    GitHubSignals.Found(now, isArchived = false),
                    listOf("v1.5.0", "v2.0.0"),
                ),
            )

        val results =
            analyzer.analyze(setOf(Coords("org.example", "widget", "1.0.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals("2.0.0", results[0].mavenSignals?.latestVersion)
    }

    @Test fun `rejects a GitHub candidate older than the unverified currentVersion signals`() {
        val unverified = MavenSignals("2.0.0", now.minus(30, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val olderCandidate = MavenSignals("1.5.0", now.minus(400, java.time.temporal.ChronoUnit.DAYS), verified = true)
        val client = stubClientWithProbe(unverified) { version -> if (version == "1.5.0") olderCandidate else null }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("org/widget"),
                stubGithubClientWithReleases(GitHubSignals.Found(now, isArchived = false), listOf("v1.5.0")),
            )

        val results =
            analyzer.analyze(setOf(Coords("org.example", "widget", "2.0.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(unverified, results[0].mavenSignals)
    }

    @Test fun `skips a pre-release candidate tag even when it resolves`() {
        val unverified = MavenSignals("1.0.0", now.minus(400, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val probedVersions = mutableListOf<String>()
        val client =
            stubClientWithProbe(unverified) { version ->
                probedVersions.add(version)
                MavenSignals(version, now, verified = true)
            }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("org/widget"),
                stubGithubClientWithReleases(GitHubSignals.Found(now, isArchived = false), listOf("v2.0.0-RC1")),
            )

        analyzer.analyze(setOf(Coords("org.example", "widget", "1.0.0")), singleRepoUrls, 12, 24, emptyList())

        assertEquals(emptyList<String>(), probedVersions)
    }

    @Test fun `continues to the next repo when one repo throws during escalation probing`() {
        val unverified = MavenSignals("1.0.0", now.minus(400, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val verifiedCandidate = MavenSignals("2.0.0", now, verified = true)
        val client =
            object : MavenMetadataClient() {
                override fun fetchSignals(
                    group: String,
                    artifact: String,
                    currentVersion: String,
                    baseUrl: String,
                ) = unverified

                override fun probeVersion(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): MavenSignals? =
                    if (baseUrl.endsWith("first")) {
                        error("simulated repo failure")
                    } else {
                        verifiedCandidate
                    }
            }
        val analyzer =
            DependencyAnalyzer(
                client,
                stubPomClient("org/widget"),
                stubGithubClientWithReleases(GitHubSignals.Found(now, isArchived = false), listOf("v2.0.0")),
            )

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "widget", "1.0.0")),
                listOf("https://repo.example.com/first", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(verifiedCandidate, results[0].mavenSignals)
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

    @Test fun `keeps walking past an unverified GREEN result to find a verified one`() {
        val unverifiedFresh = MavenSignals("8.14.4", now, verified = false)
        val verifiedOlder = MavenSignals("8.14.4", now.minus(1, java.time.temporal.ChronoUnit.DAYS), verified = true)
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
                    return if (baseUrl.endsWith("second")) verifiedOlder else unverifiedFresh
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

        assertEquals(2, calledBaseUrls.size)
        assertEquals(verifiedOlder, results[0].mavenSignals)
    }

    @Test fun `tries every repo when none are verified, keeping the freshest unverified result`() {
        val olderUnverified = MavenSignals("1.0", now.minus(10, java.time.temporal.ChronoUnit.DAYS), verified = false)
        val newerUnverified = MavenSignals("1.0", now, verified = false)
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
                    return if (baseUrl.endsWith("second")) newerUnverified else olderUnverified
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

        assertEquals(2, calledBaseUrls.size)
        assertEquals(newerUnverified, results[0].mavenSignals)
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

    @Test fun `continues to the next declared repo when the github repo lookup throws`() {
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
                    return if (baseUrl.endsWith("second")) {
                        PomFetch.Success("org/hosted")
                    } else {
                        error("simulated transient failure")
                    }
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

    @Test fun `sets githubSignals to FetchFailed when every declared repo's github lookup throws`() {
        val pomClient =
            object : PomClient() {
                override fun lookupGitHubRepo(
                    group: String,
                    artifact: String,
                    version: String,
                    baseUrl: String,
                ): PomFetch = error("simulated transient failure")
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient())

        val results =
            analyzer.analyze(
                setOf(Coords("org.example", "flaky", "1.0")),
                listOf("https://repo1.maven.org/maven2", "https://repo.example.com/second"),
                12,
                24,
                emptyList(),
            )

        assertEquals(GitHubSignals.FetchFailed, results[0].githubSignals)
    }

    @Test fun `sets githubSignals to FetchFailed when climbing to a parent throws on every declared repo`() {
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
                        error("simulated transient failure resolving the parent")
                    }
            }
        val analyzer = DependencyAnalyzer(stubClient(greenSignals), pomClient, stubGithubClient())

        val results = analyzer.analyze(setOf(childCoord), singleRepoUrls, 12, 24, emptyList())

        assertEquals(GitHubSignals.FetchFailed, results[0].githubSignals)
    }
}
