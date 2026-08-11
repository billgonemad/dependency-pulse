package com.billgonemad.dependencypulse

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

private val GITHUB_URL_PATTERN = Regex("""(?<![\w-])github\.com[/:]+([\w.-]+)/([\w.-]+)""")

private const val DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

internal fun normalizeGitHubUrl(rawUrl: String?): String? {
    val match = rawUrl?.let { GITHUB_URL_PATTERN.find(it) } ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2].removeSuffix(".git")
    return "$owner/$repo"
}

// POM-not-found (any repo that doesn't have this coordinate at all) is kept distinct from
// POM-found-but-no-scm-link: the former means the walk across declared repos should keep
// going, the latter means the walk has its authoritative answer for this GAV and should stop,
// even though that answer is "no GitHub link". See #115.
internal sealed class PomFetch {
    data class Success(
        val githubRepo: String?,
        val parentCoords: Coords? = null,
    ) : PomFetch()

    object NotFound : PomFetch()
}

open class PomClient(
    private val baseUrl: String = "https://repo1.maven.org/maven2",
    private val httpClient: HttpClient = HttpClientProvider.httpClient,
) {
    private val pomCache = ConcurrentHashMap<String, PomFetch.Success>()

    internal open fun lookupGitHubRepo(
        group: String,
        artifact: String,
        version: String,
        baseUrl: String = this.baseUrl,
    ): PomFetch {
        val url = "$baseUrl/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"
        return pomCache[url]
            ?: fetchPomBody(url)?.let { body -> parseFetch(body).also { pomCache[url] = it } }
            ?: PomFetch.NotFound
    }

    private fun parseFetch(body: String): PomFetch.Success {
        val root = parsePom(body)?.documentElement
        val scm = root?.let { firstChildElement(it, "scm") }
        val candidates =
            listOfNotNull(
                scm?.let { firstChildText(it, "url") },
                scm?.let { firstChildText(it, "connection") },
                scm?.let { firstChildText(it, "developerConnection") },
                root?.let { firstChildText(it, "url") },
            )
        val githubRepo = candidates.firstNotNullOfOrNull { normalizeGitHubUrl(it) }
        val parentCoords = root?.let { firstChildElement(it, "parent") }?.let(::parseParentCoords)
        return PomFetch.Success(githubRepo, parentCoords)
    }

    private fun parseParentCoords(parent: Element): Coords? {
        val group = firstChildText(parent, "groupId")
        val artifact = firstChildText(parent, "artifactId")
        val version = firstChildText(parent, "version")
        return if (group != null && artifact != null && version != null) {
            Coords(group, artifact, version)
        } else {
            null
        }
    }

    private fun fetchPomBody(url: String): String? {
        val response = safeGet(httpClient, url).orNull() ?: return null
        return if (response.statusCode() == HTTP_OK) response.body() else null
    }

    private fun parsePom(xml: String): Document? =
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
}
