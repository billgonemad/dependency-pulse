package com.billgonemad.dependencypulse

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubTagNormalizerTest {
    @Test fun `strips a leading v before a digit`() {
        assertEquals(listOf("v9.7.0", "9.7.0"), normalizeTagToVersionCandidates("v9.7.0", "gradle-tooling-api"))
    }

    @Test fun `strips a leading v_ prefix`() {
        assertEquals(listOf("v_2.0.18", "2.0.18"), normalizeTagToVersionCandidates("v_2.0.18", "slf4j-api"))
    }

    @Test fun `strips a leading artifactId dash prefix`() {
        assertEquals(
            listOf("jackson-databind-3.2.1", "3.2.1"),
            normalizeTagToVersionCandidates("jackson-databind-3.2.1", "jackson-databind"),
        )
    }

    @Test fun `strips a leading release- prefix`() {
        assertEquals(listOf("release-1.2.3", "1.2.3"), normalizeTagToVersionCandidates("release-1.2.3", "widget"))
    }

    @Test fun `a tag matching no rule is returned unchanged as the only candidate`() {
        assertEquals(listOf("2024.1"), normalizeTagToVersionCandidates("2024.1", "widget"))
    }

    @Test fun `known limitation- a flavor-suffixed coordinate is not synthesized from a plain v tag`() {
        // guava's real Maven coordinates are 33.6.0-jre / 33.6.0-android; no normalization rule
        // here produces a flavor suffix, so neither candidate below is the real artifact. The
        // caller's Maven probe will fail on both and correctly fall through — see the spec's
        // "Non-goals". This test documents the limitation rather than hiding it.
        assertEquals(listOf("v33.6.0", "33.6.0"), normalizeTagToVersionCandidates("v33.6.0", "guava"))
    }

    @Test fun `does not strip a leading v when not followed by a digit`() {
        assertEquals(listOf("vNext"), normalizeTagToVersionCandidates("vNext", "widget"))
    }

    @Test fun `a single character v tag is returned unchanged`() {
        assertEquals(listOf("v"), normalizeTagToVersionCandidates("v", "widget"))
    }
}
