package com.billgonemad.dependencypulse

import org.gradle.testfixtures.ProjectBuilder
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

class DependencyPulseTaskTest {
    @Test fun `analyzeDependencies closes the http client it was given`() {
        val project = ProjectBuilder.builder().build()
        val httpClient = HttpClient.newHttpClient()

        analyzeDependencies(
            project = project,
            httpClient = httpClient,
            rateLimitState = RateLimitState.local(),
            config =
                DependencyAnalysisConfig(
                    pomBaseUrl = "http://localhost:0",
                    githubApiBaseUrl = "http://localhost:0",
                    githubToken = null,
                    retryDelayMs = 0L,
                    ignoreConfigurations = emptyList(),
                    yellowAfterMonths = 12,
                    redAfterMonths = 24,
                    knownStableGroups = emptyList(),
                ),
        )

        assertTrue(httpClient.isTerminated, "httpClient should be closed once analyzeDependencies returns")
    }
}
