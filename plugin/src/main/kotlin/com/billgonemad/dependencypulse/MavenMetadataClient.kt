package com.billgonemad.dependencypulse

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

private const val MAX_RETRIES = 3
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
private val RETRYABLE_CODES =
    setOf(
        HTTP_TOO_MANY_REQUESTS,
        HTTP_INTERNAL_SERVER_ERROR,
        HTTP_BAD_GATEWAY,
        HTTP_SERVICE_UNAVAILABLE,
        HTTP_GATEWAY_TIMEOUT,
    )
private const val DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

open class MavenMetadataClient(
    private val baseUrl: String = "https://repo1.maven.org/maven2",
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
    private val retryDelayMs: Long = 1_000L,
) {
    private val metadataCache = ConcurrentHashMap<String, ArtifactMetadata>()
    private val lastModifiedCache = ConcurrentHashMap<String, Instant>()

    open fun fetchSignals(
        group: String,
        artifact: String,
        currentVersion: String,
    ): MavenSignals? {
        val metadata = fetchMetadata(group, artifact) ?: return null
        return selectLatestVersion(metadata.latest, metadata.orderedVersions, currentVersion)?.let { selected ->
            MavenSignals(latestVersion = selected, latestReleaseDate = fetchLastModified(group, artifact, selected))
        }
    }

    private fun fetchMetadata(
        group: String,
        artifact: String,
    ): ArtifactMetadata? {
        val url = "$baseUrl/${group.replace('.', '/')}/$artifact/maven-metadata.xml"
        metadataCache[url]?.let { return it }
        val response = getWithRetry(url)
        return when {
            response == null -> throw IOException("Maven repository unreachable for $url")
            response.statusCode() == HTTP_NOT_FOUND -> null
            response.statusCode() == HTTP_OK -> parseMetadata(response.body(), url).also { metadataCache[url] = it }
            else -> throw IOException("Maven repository returned HTTP ${response.statusCode()} for $url")
        }
    }

    private fun fetchLastModified(
        group: String,
        artifact: String,
        version: String,
    ): Instant {
        val url = "$baseUrl/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"
        lastModifiedCache[url]?.let { return it }
        val response = getWithRetry(url)
        if (response == null || response.statusCode() != HTTP_OK) {
            throw IOException("Maven repository returned HTTP ${response?.statusCode()} for $url")
        }
        val header =
            response.headers().firstValue("Last-Modified").orElse(null)
                ?: throw IOException("Missing Last-Modified header for $url")
        val instant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header))
        lastModifiedCache[url] = instant
        return instant
    }

    private fun getWithRetry(url: String): HttpResponse<String>? {
        var attempt = 0
        var result: HttpResponse<String>? = null
        while (true) {
            val outcome = safeGet(httpClient, url)
            result = outcome.orNull()
            val networkRetryable = outcome is SafeGetResult.Failure && outcome.retryable
            val statusRetryable = result?.statusCode() in RETRYABLE_CODES
            val canRetry = (networkRetryable || statusRetryable) && attempt < MAX_RETRIES
            if (!canRetry) break
            Thread.sleep(retryDelayMs * (1L shl attempt))
            attempt++
        }
        return result
    }

    private fun parseMetadata(
        xml: String,
        url: String,
    ): ArtifactMetadata {
        val document = parseXml(xml) ?: throw IOException("Malformed maven-metadata.xml from $url")
        val versioning = requireChild(firstChildElement(document.documentElement, "versioning"), "versioning", url)
        val latest = requireChild(firstChildText(versioning, "latest"), "latest", url)
        val versions = firstChildElement(versioning, "versions")?.let { allChildText(it, "version") } ?: emptyList()
        return ArtifactMetadata(latest, versions)
    }

    private fun <T> requireChild(
        value: T?,
        tagName: String,
        url: String,
    ): T = value ?: throw IOException("Missing <$tagName> in maven-metadata.xml from $url")

    private fun parseXml(xml: String): Document? =
        try {
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    setFeature(DISALLOW_DOCTYPE_FEATURE, true)
                    isExpandEntityReferences = false
                }
            factory.newDocumentBuilder().parse(xml.byteInputStream())
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            null
        }

    private fun firstChildElement(
        parent: Element,
        tagName: String,
    ): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tagName) return node
        }
        return null
    }

    private fun firstChildText(
        parent: Element,
        tagName: String,
    ): String? = firstChildElement(parent, tagName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun allChildText(
        parent: Element,
        tagName: String,
    ): List<String> {
        val children = parent.childNodes
        val result = mutableListOf<String>()
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tagName) {
                node.textContent
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { result.add(it) }
            }
        }
        return result
    }
}

private data class ArtifactMetadata(
    val latest: String,
    val orderedVersions: List<String>,
)
