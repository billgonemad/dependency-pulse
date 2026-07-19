package com.billgonemad.dependencypulse

import java.net.http.HttpClient

// A plain object, not a Gradle BuildService: BuildServices are scoped to a single build,
// so they can't give us what we actually want here (one HttpClient reused across separate
// `gradle` invocations against the same warm daemon). The Gradle daemon reuses the plugin's
// classloader across consecutive builds when its classpath is unchanged, so this object's
// state — and this HttpClient — persists across those invocations. If the daemon restarts
// or the classpath changes, a fresh client is simply (re)created here; that's a missed reuse
// opportunity, not a correctness problem.
internal object HttpClientProvider {
    val httpClient: HttpClient by lazy { newDefaultHttpClient() }
}
