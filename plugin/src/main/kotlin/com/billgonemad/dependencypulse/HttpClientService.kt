package com.billgonemad.dependencypulse

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.net.http.HttpClient

abstract class HttpClientService : BuildService<BuildServiceParameters.None> {
    val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}
