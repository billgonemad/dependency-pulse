package com.billgonemad.dependencypulse

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
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
    ) = DependencyInfo(
        group = group,
        artifact = artifact,
        currentVersion = version,
        mavenSignals = signals,
        githubSignals = null,
        javaxBlocker = false,
        status = status,
        errorMessage = errorMessage,
    )

    @Test fun `GREEN dep shows checkmark and Active`() {
        val now = Instant.now()
        val output =
            capture {
                ReportPrinter.print(listOf(dep(signals = MavenSignals("1.0", now, now), status = DepStatus.GREEN)))
            }
        assertTrue(output.contains("✅"))
        assertTrue(output.contains("Active"))
    }

    @Test fun `YELLOW dep shows warning emoji`() {
        val old = Instant.now().minus(400, ChronoUnit.DAYS)
        val output =
            capture {
                ReportPrinter.print(listOf(dep(signals = MavenSignals("1.0", old, old), status = DepStatus.YELLOW)))
            }
        assertTrue(output.contains("⚠️"))
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
                dep(signals = MavenSignals("1.0", now, now), status = DepStatus.GREEN),
                dep(signals = MavenSignals("1.0", now.minus(400, ChronoUnit.DAYS), null), status = DepStatus.YELLOW),
                dep(status = DepStatus.RED),
                dep(status = DepStatus.UNKNOWN, errorMessage = "err"),
            )
        val output = capture { ReportPrinter.print(deps) }
        assertTrue(output.contains("4 dependencies scanned"))
        assertTrue(output.contains("1 red"))
        assertTrue(output.contains("1 yellow"))
        assertTrue(output.contains("1 green"))
        assertTrue(output.contains("1 unknown"))
    }
}
