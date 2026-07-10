package com.billgonemad.dependencypulse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionSelectorTest {
    @Test fun `classifies qualifier tokens as pre-release`() {
        listOf(
            "5.0.0-alpha.16",
            "1.0.0-beta",
            "2.0-rc1",
            "3.1.0.CR2",
            "1.5.0.M1",
            "1.5.0-milestone-3",
            "9.0-SNAPSHOT",
            "1.0-preview",
            "0.9-dev",
            "2.0.0.Final-EAP",
            "1.0-pre",
        ).forEach { assertTrue(isPreRelease(it), "expected pre-release: $it") }
    }

    @Test fun `classifies plain and release-qualified versions as stable`() {
        listOf("4.12.0", "1.0.0.RELEASE", "2.0-Final", "10.20.30", "1.0").forEach {
            assertFalse(isPreRelease(it), "expected stable: $it")
        }
    }

    @Test fun `classifies date-stamped legacy builds as timestamp versions`() {
        listOf(
            "20040616",
            "20030418.083655",
            "20031027.000000",
            "20040102.233541",
        ).forEach { assertTrue(isTimestampVersion(it), "expected timestamp version: $it") }
    }

    @Test fun `does not classify normal versions as timestamp versions`() {
        listOf("4.12.0", "1.0.0.RELEASE", "3.2.2", "10.20.30", "1.0", "5.0.0-alpha.16", "2.0.20020914.015953").forEach {
            assertFalse(isTimestampVersion(it), "expected non-timestamp: $it")
        }
    }

    @Test fun `does not classify near-miss digit counts as timestamp versions`() {
        listOf("1234567", "123456789", "20040616.0836", "20040616.08365").forEach {
            assertFalse(isTimestampVersion(it), "expected non-timestamp: $it")
        }
    }

    @Test fun `selects newest stable over a newer pre-release`() {
        // okhttp scenario: 5.0.0-alpha.16 is newest overall, but 4.12.0 is newest stable.
        val ordered = listOf("4.11.0", "4.12.0", "5.0.0-alpha.16")
        assertEquals("4.12.0", selectLatestVersion("5.0.0-alpha.16", ordered, "4.12.0"))
    }

    @Test fun `skips timestamp-formatted versions when selecting latest stable`() {
        // commons-collections:commons-collections real maven-metadata.xml (issue #59,
        // verified against live repo1.maven.org): four bare-timestamp builds were
        // registered after 3.2.2 in Central's append-order list, and <latest> itself
        // is the date-stamped 20040616.
        val ordered =
            listOf(
                "1.0",
                "2.0",
                "2.0.20020914.015953",
                "2.0.20020914.020746",
                "2.0.20020914.020858",
                "2.1",
                "2.1.1",
                "3.0",
                "3.0-dev2",
                "3.1",
                "3.2",
                "3.2.1",
                "3.2.2",
                "20030418.083655",
                "20031027.000000",
                "20040102.233541",
                "20040616",
            )
        assertEquals("3.2.2", selectLatestVersion("20040616", ordered, "3.2.2"))
    }

    @Test fun `includes pre-releases when current version is a pre-release`() {
        val ordered = listOf("4.12.0", "5.0.0-alpha.16")
        assertEquals("5.0.0-alpha.16", selectLatestVersion("5.0.0-alpha.16", ordered, "5.0.0-alpha.14"))
    }

    @Test fun `falls back to newest pre-release when no stable exists`() {
        val ordered = listOf("1.0.0-alpha.1", "1.0.0-alpha.2")
        assertEquals("1.0.0-alpha.2", selectLatestVersion("1.0.0-alpha.2", ordered, "1.0.0"))
    }

    @Test fun `returns null for empty version list`() {
        assertNull(selectLatestVersion("1.0.0", emptyList(), "1.0.0"))
    }
}
