package com.billgonemad.dependencypulse

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownStableGroupsTest {
    @Test fun `matches a group-prefix entry`() {
        val coord = Coords("jakarta.annotation", "jakarta.annotation-api", "3.0.0")
        assertTrue(matchesKnownStableGroup(coord, listOf("jakarta.")))
    }

    @Test fun `does not match when no entry applies`() {
        val coord = Coords("com.example", "foo", "1.0")
        assertFalse(matchesKnownStableGroup(coord, listOf("jakarta.", "javax.")))
    }

    @Test fun `matches an exact group-artifact coordinate entry`() {
        val coord = Coords("com.google.code.findbugs", "jsr305", "3.0.2")
        assertTrue(matchesKnownStableGroup(coord, listOf("com.google.code.findbugs:jsr305")))
    }

    @Test fun `does not match a different artifact under the same group as an exact-coordinate entry`() {
        val coord = Coords("com.google.code.findbugs", "other-artifact", "1.0")
        assertFalse(matchesKnownStableGroup(coord, listOf("com.google.code.findbugs:jsr305")))
    }

    @Test fun `returns false for an empty knownStableGroups list`() {
        val coord = Coords("jakarta.annotation", "jakarta.annotation-api", "3.0.0")
        assertFalse(matchesKnownStableGroup(coord, emptyList()))
    }
}
