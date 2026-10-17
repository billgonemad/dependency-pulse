package com.billgonemad.dependencypulse

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportRendererTest {
    @Suppress("LongParameterList")
    private fun dep(
        group: String = "org.example",
        artifact: String = "lib",
        version: String = "1.0",
        signals: MavenSignals? = null,
        status: DepStatus = DepStatus.GREEN,
        errorMessage: String? = null,
        githubSignals: GitHubSignals = GitHubSignals.NoRepo,
        knownStable: Boolean = false,
    ) = DependencyInfo(
        group = group,
        artifact = artifact,
        currentVersion = version,
        mavenSignals = signals,
        githubSignals = githubSignals,
        javaxBlocker = false,
        status = status,
        errorMessage = errorMessage,
        knownStable = knownStable,
    )

    @Test fun `GREEN dep shows checkmark and Active`() {
        val now = Instant.now()
        val depList = listOf(dep(signals = MavenSignals("1.0", now), status = DepStatus.GREEN))
        val output = ReportRenderer.render(depList, now = now)
        assertTrue(output.contains("✅"))
        assertTrue(output.contains("Active"))
        assertTrue(output.contains("0 months ago"))
    }

    @Test fun `YELLOW dep shows warning emoji`() {
        val refNow = Instant.now()
        val old = refNow.minus(400, ChronoUnit.DAYS)
        val depList = listOf(dep(signals = MavenSignals("1.0", old), status = DepStatus.YELLOW))
        val output = ReportRenderer.render(depList, now = refNow)
        assertTrue(output.contains("⚠️"))
        assertTrue(output.contains("13 months ago"))
    }

    @Test fun `RED dep with null signals shows not-published message`() {
        val output =
            ReportRenderer.render(listOf(dep(status = DepStatus.RED)))
        assertTrue(output.contains("🔴"))
        assertTrue(output.contains("no longer published"))
    }

    @Test fun `unverified signals print the honest fallback label instead of the raw version`() {
        val now = Instant.now()
        val depList =
            listOf(
                dep(
                    signals = MavenSignals("8.14.4", now.minus(180, ChronoUnit.DAYS), verified = false),
                    status = DepStatus.GREEN,
                ),
            )
        val output = ReportRenderer.render(depList, now = now)
        assertTrue(output.contains("Latest: unknown (repository metadata incomplete)"))
        assertFalse(output.contains("Latest: 8.14.4"))
        assertTrue(output.contains("6 months ago"))
    }

    @Test fun `UNKNOWN dep shows question mark and unavailable message`() {
        val output =
            ReportRenderer.render(listOf(dep(status = DepStatus.UNKNOWN, errorMessage = "timeout")))
        assertTrue(output.contains("❓"))
        assertTrue(output.contains("unavailable"))
    }

    @Test fun `summary line shows correct counts`() {
        val now = Instant.now()
        val deps =
            listOf(
                dep(signals = MavenSignals("1.0", now), status = DepStatus.GREEN),
                dep(signals = MavenSignals("1.0", now.minus(400, ChronoUnit.DAYS)), status = DepStatus.YELLOW),
                dep(status = DepStatus.RED),
                dep(status = DepStatus.UNKNOWN, errorMessage = "err"),
            )
        val output = ReportRenderer.render(deps, now = Instant.now())
        assertTrue(output.contains("4 dependencies scanned"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("1 yellow"))
        assertTrue(output.contains("1 green"))
        assertTrue(output.contains("1 unknown"))
    }

    @Test fun `known-stable dep with Maven signals shows the spec marker`() {
        val now = Instant.now()
        val depList =
            listOf(
                dep(
                    signals = MavenSignals("3.0.0", now.minus(900, ChronoUnit.DAYS)),
                    status = DepStatus.RED,
                    knownStable = true,
                ),
            )
        val output = ReportRenderer.render(depList, now = now)
        assertTrue(output.contains("📘"))
        assertTrue(output.contains("Spec (stable) | Latest: 3.0.0"))
        assertFalse(output.contains("🔴"))
    }

    @Test fun `known-stable dep with no Maven signals still shows the UNKNOWN presentation`() {
        val depList = listOf(dep(status = DepStatus.UNKNOWN, errorMessage = "timeout", knownStable = true))
        val output = ReportRenderer.render(depList)
        assertTrue(output.contains("❓"))
        assertTrue(output.contains("unavailable"))
        assertFalse(output.contains("📘"))
        assertFalse(output.contains("Spec (stable)"))
    }

    @Test fun `known-stable GREEN dep still shows the Active suffix alongside the spec marker`() {
        val now = Instant.now()
        val depList = listOf(dep(signals = MavenSignals("3.0.0", now), status = DepStatus.GREEN, knownStable = true))
        val output = ReportRenderer.render(depList, now = now)
        assertTrue(output.contains("Spec (stable) | Latest: 3.0.0"))
        assertTrue(output.contains("| Active"))
    }

    @Test fun `summary counts known-stable deps separately from red, yellow, green, and unknown`() {
        val now = Instant.now()
        val deps =
            listOf(
                dep(signals = MavenSignals("3.0.0", now), status = DepStatus.GREEN, knownStable = true),
                dep(status = DepStatus.RED),
            )
        val output = ReportRenderer.render(deps, now = now)
        assertTrue(output.contains("2 dependencies scanned"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("0 green"))
        assertTrue(output.contains("1 stable"))
    }

    @Test fun `known-stable dep with an archived GitHub repo is not relabeled stable`() {
        val now = Instant.now()
        val depList =
            listOf(
                dep(
                    signals = MavenSignals("3.0.0", now.minus(60, ChronoUnit.DAYS)),
                    status = DepStatus.RED,
                    knownStable = true,
                    githubSignals = GitHubSignals.Archived,
                ),
            )
        val output = ReportRenderer.render(depList, now = now)
        assertTrue(output.contains("🔴"))
        assertTrue(output.contains("GitHub: Repo archived"))
        assertFalse(output.contains("📘"))
        assertFalse(output.contains("Spec (stable)"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("0 stable"))
    }

    @Test fun `SUMMARY_ONLY hides all per-dependency lines but keeps footer counts`() {
        val now = Instant.now()
        val deps =
            listOf(
                dep(signals = MavenSignals("1.0", now), status = DepStatus.GREEN),
                dep(status = DepStatus.RED),
            )
        val output = ReportRenderer.render(deps, now = now, outputLevel = OutputLevel.SUMMARY_ONLY)
        assertFalse(output.contains("✅"))
        assertFalse(output.contains("🔴"))
        assertFalse(output.contains("Latest:"))
        assertTrue(output.contains("2 dependencies scanned"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("1 green"))
    }

    @Test fun `DEFAULT hides plain GREEN but keeps stable, yellow, red, and unknown lines`() {
        val now = Instant.now()
        val deps =
            listOf(
                dep(signals = MavenSignals("1.0", now), status = DepStatus.GREEN),
                dep(
                    signals = MavenSignals("3.0.0", now.minus(900, ChronoUnit.DAYS)),
                    status = DepStatus.RED,
                    knownStable = true,
                ),
                dep(signals = MavenSignals("1.0", now.minus(400, ChronoUnit.DAYS)), status = DepStatus.YELLOW),
                dep(status = DepStatus.RED),
                dep(status = DepStatus.UNKNOWN, errorMessage = "timeout"),
            )
        val output = ReportRenderer.render(deps, now = now, outputLevel = OutputLevel.DEFAULT)
        assertFalse(output.contains("✅"))
        assertTrue(output.contains("📘"))
        assertTrue(output.contains("⚠️"))
        assertTrue(output.contains("🔴"))
        assertTrue(output.contains("❓"))
        assertTrue(output.contains("5 dependencies scanned"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("1 yellow"))
        assertTrue(output.contains("1 green"))
        assertTrue(output.contains("1 unknown"))
        assertTrue(output.contains("1 stable"))
    }

    @Test fun `VERBOSE shows plain GREEN lines exactly like today's default`() {
        val now = Instant.now()
        val depList = listOf(dep(signals = MavenSignals("1.0", now), status = DepStatus.GREEN))
        val output = ReportRenderer.render(depList, now = now, outputLevel = OutputLevel.VERBOSE)
        assertTrue(output.contains("✅"))
        assertTrue(output.contains("Active"))
    }

    @Test fun `archived GitHub repo shows GitHub archived line`() {
        val depList =
            listOf(
                dep(
                    status = DepStatus.RED,
                    githubSignals = GitHubSignals.Archived,
                ),
            )
        val output = ReportRenderer.render(depList)
        assertTrue(output.contains("GitHub: Repo archived"))
    }

    @Test fun `stale GitHub commit shows last-commit-months-ago line`() {
        val now = Instant.now()
        val old = now.minus(420, ChronoUnit.DAYS)
        val depList =
            listOf(
                dep(
                    status = DepStatus.YELLOW,
                    githubSignals = GitHubSignals.Found(old),
                ),
            )
        val output = ReportRenderer.render(depList, now = now)
        assertTrue(output.contains("GitHub: Last commit 14 months ago"))
    }

    @Test fun `NoRepo GitHub signal shows no GitHub line`() {
        val depList = listOf(dep(githubSignals = GitHubSignals.NoRepo))
        val output = ReportRenderer.render(depList)
        assertFalse(output.contains("GitHub:"))
    }

    @Test fun `RateLimited GitHub signal shows a skipped message`() {
        val depList = listOf(dep(githubSignals = GitHubSignals.RateLimited))
        val output = ReportRenderer.render(depList)
        assertTrue(output.contains("GitHub: check skipped (rate limited)"))
    }

    @Test fun `FetchFailed GitHub signal shows a skipped message`() {
        val depList = listOf(dep(githubSignals = GitHubSignals.FetchFailed))
        val output = ReportRenderer.render(depList)
        assertTrue(output.contains("GitHub: check skipped (fetch failed)"))
    }

    @Test fun `summary line reports GitHub checks skipped due to rate limiting`() {
        val deps =
            listOf(
                dep(githubSignals = GitHubSignals.RateLimited),
                dep(githubSignals = GitHubSignals.RateLimited),
                dep(signals = MavenSignals("1.0", Instant.now()), status = DepStatus.GREEN),
            )
        val output = ReportRenderer.render(deps)
        assertTrue(output.contains("2 GitHub checks skipped (2 rate limited)."))
    }

    @Test fun `summary line reports GitHub checks skipped due to fetch failures`() {
        val deps = listOf(dep(githubSignals = GitHubSignals.FetchFailed))
        val output = ReportRenderer.render(deps)
        assertTrue(output.contains("1 GitHub check skipped (1 fetch failed)."))
    }

    @Test fun `summary line combines rate-limited and fetch-failed skip counts`() {
        val deps =
            listOf(
                dep(githubSignals = GitHubSignals.RateLimited),
                dep(githubSignals = GitHubSignals.FetchFailed),
            )
        val output = ReportRenderer.render(deps)
        assertTrue(output.contains("2 GitHub checks skipped (1 rate limited, 1 fetch failed)."))
    }

    @Test fun `summary line reports empty GitHub repos`() {
        val deps = listOf(dep(githubSignals = GitHubSignals.EmptyRepo))
        val output = ReportRenderer.render(deps)
        assertTrue(output.contains("1 GitHub repo is empty."))
    }

    @Test fun `summary line pluralises the empty-repo count`() {
        val deps = listOf(dep(githubSignals = GitHubSignals.EmptyRepo), dep(githubSignals = GitHubSignals.EmptyRepo))
        val output = ReportRenderer.render(deps)
        assertTrue(output.contains("2 GitHub repos are empty."))
    }

    @Test fun `summary line omits the empty-repo clause when no GitHub repo is empty`() {
        val deps = listOf(dep(githubSignals = GitHubSignals.Found(Instant.now())))
        val output = ReportRenderer.render(deps)
        assertFalse(output.contains("empty"))
    }

    @Test fun `DEFAULT mode hides the empty-repo row for a plain-GREEN dep but keeps the summary count`() {
        val now = Instant.now()
        val deps =
            listOf(
                dep(
                    signals = MavenSignals("1.0", now),
                    status = DepStatus.GREEN,
                    githubSignals = GitHubSignals.EmptyRepo,
                ),
            )
        val output = ReportRenderer.render(deps, now = now, outputLevel = OutputLevel.DEFAULT)
        assertFalse(output.contains("GitHub: repository is empty"))
        assertTrue(output.contains("1 GitHub repo is empty."))
    }

    @Test fun `summary line omits skipped clause when no GitHub checks were skipped`() {
        val deps = listOf(dep(githubSignals = GitHubSignals.NoRepo))
        val output = ReportRenderer.render(deps)
        assertFalse(output.contains("skipped"))
    }

    @Test fun `DEFAULT mode hides the skipped row for a plain-GREEN dep but keeps the summary count`() {
        val now = Instant.now()
        val deps =
            listOf(
                dep(
                    signals = MavenSignals("1.0", now),
                    status = DepStatus.GREEN,
                    githubSignals = GitHubSignals.RateLimited,
                ),
            )
        val output = ReportRenderer.render(deps, now = now, outputLevel = OutputLevel.DEFAULT)
        assertFalse(output.contains("GitHub: check skipped"))
        assertTrue(output.contains("1 GitHub check skipped (1 rate limited)."))
    }

    @Test fun `EmptyRepo GitHub signal shows an empty-repository line`() {
        val output = ReportRenderer.render(listOf(dep(githubSignals = GitHubSignals.EmptyRepo)))
        assertTrue(output.contains("GitHub: repository is empty (no commits)"))
    }
}
