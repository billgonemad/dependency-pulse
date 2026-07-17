package com.billgonemad.dependencypulse

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutputLevelResolverTest {
    @Test fun `no CLI and no extension config resolves to DEFAULT`() {
        val level =
            resolveOutputLevel(
                cliSummaryOnly = false,
                cliShowGreen = false,
                extSummaryOnly = false,
                extShowGreen = false,
            )
        assertEquals(OutputLevel.DEFAULT, level)
    }

    @Test fun `extension summaryOnly alone resolves to SUMMARY_ONLY`() {
        val level =
            resolveOutputLevel(
                cliSummaryOnly = false,
                cliShowGreen = false,
                extSummaryOnly = true,
                extShowGreen = false,
            )
        assertEquals(OutputLevel.SUMMARY_ONLY, level)
    }

    @Test fun `extension showGreen alone resolves to VERBOSE`() {
        val level =
            resolveOutputLevel(
                cliSummaryOnly = false,
                cliShowGreen = false,
                extSummaryOnly = false,
                extShowGreen = true,
            )
        assertEquals(OutputLevel.VERBOSE, level)
    }

    @Test fun `CLI showGreen overrides extension summaryOnly`() {
        val level =
            resolveOutputLevel(
                cliSummaryOnly = false,
                cliShowGreen = true,
                extSummaryOnly = true,
                extShowGreen = false,
            )
        assertEquals(OutputLevel.VERBOSE, level)
    }

    @Test fun `CLI summaryOnly overrides extension showGreen`() {
        val level =
            resolveOutputLevel(
                cliSummaryOnly = true,
                cliShowGreen = false,
                extSummaryOnly = false,
                extShowGreen = true,
            )
        assertEquals(OutputLevel.SUMMARY_ONLY, level)
    }

    @Test fun `both CLI flags set throws with the CLI conflict message`() {
        val exception =
            assertFailsWith<GradleException> {
                resolveOutputLevel(
                    cliSummaryOnly = true,
                    cliShowGreen = true,
                    extSummaryOnly = false,
                    extShowGreen = false,
                )
            }
        assertEquals("--summary-only and --show-green are mutually exclusive", exception.message)
    }

    @Test fun `both extension properties set throws with the extension conflict message when no CLI flag is set`() {
        val exception =
            assertFailsWith<GradleException> {
                resolveOutputLevel(
                    cliSummaryOnly = false,
                    cliShowGreen = false,
                    extSummaryOnly = true,
                    extShowGreen = true,
                )
            }
        assertEquals(
            "dependencyPulse.summaryOnly and dependencyPulse.showGreen are mutually exclusive",
            exception.message,
        )
    }

    @Test fun `a valid CLI flag does not mask a contradictory extension config`() {
        val exception =
            assertFailsWith<GradleException> {
                resolveOutputLevel(
                    cliSummaryOnly = false,
                    cliShowGreen = true,
                    extSummaryOnly = true,
                    extShowGreen = true,
                )
            }
        assertEquals(
            "dependencyPulse.summaryOnly and dependencyPulse.showGreen are mutually exclusive",
            exception.message,
        )
    }
}
