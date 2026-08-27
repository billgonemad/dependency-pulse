package com.billgonemad.dependencypulse

import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HttpClientProviderTest {
    @Test fun `httpClient is the same instance across repeated access`() {
        val first = HttpClientProvider.httpClient
        val second = HttpClientProvider.httpClient

        assertSame(first, second, "the HttpClient should be memoized, not rebuilt on every access")
    }

    @Test fun `httpClient follows redirects`() {
        assertEquals(HttpClient.Redirect.NORMAL, HttpClientProvider.httpClient.followRedirects())
    }

    @Test fun `githubHttpClient is the same instance across repeated access`() {
        val first = HttpClientProvider.githubHttpClient
        val second = HttpClientProvider.githubHttpClient

        assertSame(first, second, "the HttpClient should be memoized, not rebuilt on every access")
    }

    @Test fun `githubHttpClient does not auto-follow redirects`() {
        assertEquals(HttpClient.Redirect.NEVER, HttpClientProvider.githubHttpClient.followRedirects())
    }
}
