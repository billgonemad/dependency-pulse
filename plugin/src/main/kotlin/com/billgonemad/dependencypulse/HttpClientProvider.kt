package com.billgonemad.dependencypulse

import java.net.http.HttpClient

// A plain object, not a Gradle BuildService: BuildServices are scoped to a single build, so
// they can't give us what we want here — one HttpClient reused across separate `gradle`
// invocations against the same warm daemon. The daemon reuses the plugin's classloader
// across builds when its classpath is unchanged, so this object's state persists instead.
internal object HttpClientProvider {
    val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}
