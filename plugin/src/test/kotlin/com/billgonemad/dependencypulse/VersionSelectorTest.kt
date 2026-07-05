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

    @Test fun `selects newest stable over a newer pre-release`() {
        // okhttp scenario: 5.0.0-alpha.16 is newest overall, but 4.12.0 is newest stable.
        val ordered = listOf("4.11.0", "4.12.0", "5.0.0-alpha.16")
        assertEquals("4.12.0", selectLatestVersion("5.0.0-alpha.16", ordered, "4.12.0"))
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
