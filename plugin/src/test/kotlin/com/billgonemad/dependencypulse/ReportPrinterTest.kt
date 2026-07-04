package com.billgonemad.dependencypulse

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportPrinterTest {
    private fun capture(block: () -> Unit): String {
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(old)
        }
        return buf.toString()
    }

    @Suppress("LongParameterList")
    private fun dep(
        group: String = "org.example",
        artifact: String = "lib",
        version: String = "1.0",
        signals: MavenSignals? = null,
        status: DepStatus = DepStatus.GREEN,
        errorMessage: String? = null,
        githubSignals: GitHubSignals = GitHubSignals.NoRepo,
    ) = DependencyInfo(
        group = group,
        artifact = artifact,
        currentVersion = version,
        mavenSignals = signals,
        githubSignals = githubSignals,
        javaxBlocker = false,
        status = status,
        errorMessage = errorMessage,
    )

    @Test fun `GREEN dep shows checkmark and Active`() {
        val now = Instant.now()
        val depList = listOf(dep(signals = MavenSignals("1.0", now), status = DepStatus.GREEN))
        val output = capture { ReportPrinter.print(depList, now = now) }
        assertTrue(output.contains("✅"))
        assertTrue(output.contains("Active"))
        assertTrue(output.contains("0 months ago"))
    }

    @Test fun `YELLOW dep shows warning emoji`() {
        val refNow = Instant.now()
        val old = refNow.minus(400, ChronoUnit.DAYS)
        val depList = listOf(dep(signals = MavenSignals("1.0", old), status = DepStatus.YELLOW))
        val output = capture { ReportPrinter.print(depList, now = refNow) }
        assertTrue(output.contains("⚠️"))
        assertTrue(output.contains("13 months ago"))
    }

    @Test fun `RED dep with null signals shows not-published message`() {
        val output =
            capture {
                ReportPrinter.print(listOf(dep(status = DepStatus.RED)))
            }
        assertTrue(output.contains("🔴"))
        assertTrue(output.contains("no longer published"))
    }

    @Test fun `UNKNOWN dep shows question mark and unavailable message`() {
        val output =
            capture {
                ReportPrinter.print(listOf(dep(status = DepStatus.UNKNOWN, errorMessage = "timeout")))
            }
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
        val output = capture { ReportPrinter.print(deps, now = Instant.now()) }
        assertTrue(output.contains("4 dependencies scanned"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("1 yellow"))
        assertTrue(output.contains("1 green"))
        assertTrue(output.contains("1 unknown"))
    }

    @Test fun `archived GitHub repo shows GitHub archived line`() {
        val depList =
            listOf(
                dep(
                    status = DepStatus.RED,
                    githubSignals = GitHubSignals.Found(lastCommitDate = null, isArchived = true),
                ),
            )
        val output = capture { ReportPrinter.print(depList) }
        assertTrue(output.contains("GitHub: Repo archived"))
    }

    @Test fun `stale GitHub commit shows last-commit-months-ago line`() {
        val now = Instant.now()
        val old = now.minus(420, ChronoUnit.DAYS)
        val depList =
            listOf(
                dep(
                    status = DepStatus.YELLOW,
                    githubSignals = GitHubSignals.Found(lastCommitDate = old, isArchived = false),
                ),
            )
        val output = capture { ReportPrinter.print(depList, now = now) }
        assertTrue(output.contains("GitHub: Last commit 14 months ago"))
    }

    @Test fun `NoRepo GitHub signal shows no GitHub line`() {
        val depList = listOf(dep(githubSignals = GitHubSignals.NoRepo))
        val output = capture { ReportPrinter.print(depList) }
        assertFalse(output.contains("GitHub:"))
    }

    @Test fun `RateLimited GitHub signal shows no GitHub line`() {
        val depList = listOf(dep(githubSignals = GitHubSignals.RateLimited))
        val output = capture { ReportPrinter.print(depList) }
        assertFalse(output.contains("GitHub:"))
    }

    @Test fun `FetchFailed GitHub signal shows no GitHub line`() {
        val depList = listOf(dep(githubSignals = GitHubSignals.FetchFailed))
        val output = capture { ReportPrinter.print(depList) }
        assertFalse(output.contains("GitHub:"))
    }

    @Test fun `Found with null lastCommitDate and not archived shows no GitHub line`() {
        val depList =
            listOf(dep(githubSignals = GitHubSignals.Found(lastCommitDate = null, isArchived = false)))
        val output = capture { ReportPrinter.print(depList) }
        assertFalse(output.contains("GitHub:"))
    }
}
