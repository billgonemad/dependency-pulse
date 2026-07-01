package com.billgonemad.dependencypulse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PomClientTest {
    @Test fun `normalizes a plain https github url`() {
        assertEquals("owner/repo", normalizeGitHubUrl("https://github.com/owner/repo"))
    }

    @Test fun `normalizes an https github url with dot-git suffix`() {
        assertEquals("owner/repo", normalizeGitHubUrl("https://github.com/owner/repo.git"))
    }

    @Test fun `normalizes a scm git plus ssh form`() {
        assertEquals("owner/repo", normalizeGitHubUrl("scm:git:git@github.com:owner/repo.git"))
    }

    @Test fun `normalizes a scm git plus https form`() {
        assertEquals("owner/repo", normalizeGitHubUrl("scm:git:https://github.com/owner/repo.git"))
    }

    @Test fun `normalizes an https url with a trailing slash`() {
        assertEquals("owner/repo", normalizeGitHubUrl("https://github.com/owner/repo/"))
    }

    @Test fun `returns null for a non-github host`() {
        assertNull(normalizeGitHubUrl("https://gitlab.com/owner/repo"))
    }

    @Test fun `returns null for a null input`() {
        assertNull(normalizeGitHubUrl(null))
    }

    @Test fun `returns null for a blank string`() {
        assertNull(normalizeGitHubUrl(""))
    }
}
