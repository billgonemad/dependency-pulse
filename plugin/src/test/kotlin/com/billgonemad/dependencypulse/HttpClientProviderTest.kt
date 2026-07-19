package com.billgonemad.dependencypulse

import kotlin.test.Test
import kotlin.test.assertSame

class HttpClientProviderTest {
    @Test fun `httpClient is the same instance across repeated access`() {
        val first = HttpClientProvider.httpClient
        val second = HttpClientProvider.httpClient

        assertSame(first, second, "the HttpClient should be memoized, not rebuilt on every access")
    }
}
