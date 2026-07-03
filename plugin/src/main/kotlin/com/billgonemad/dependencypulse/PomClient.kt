package com.billgonemad.dependencypulse

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.net.http.HttpClient
import javax.xml.parsers.DocumentBuilderFactory

private val GITHUB_URL_PATTERN = Regex("""(?<![\w-])github\.com[/:]+([\w.-]+)/([\w.-]+)""")

private const val DISALLOW_DOCTYPE_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl"

internal fun normalizeGitHubUrl(rawUrl: String?): String? {
    val match = rawUrl?.let { GITHUB_URL_PATTERN.find(it) } ?: return null
    val owner = match.groupValues[1]
    val repo = match.groupValues[2].removeSuffix(".git")
    return "$owner/$repo"
}

open class PomClient(
    private val baseUrl: String = "https://repo1.maven.org/maven2",
    private val httpClient: HttpClient = HttpClient.newBuilder().build(),
) {
    open fun fetchGitHubRepo(
        group: String,
        artifact: String,
        version: String,
    ): String? {
        val root = fetchPomBody(group, artifact, version)?.let { parsePom(it) }?.documentElement
        val scm = root?.let { firstChildElement(it, "scm") }
        val candidates =
            listOfNotNull(
                scm?.let { firstChildText(it, "url") },
                scm?.let { firstChildText(it, "connection") },
                scm?.let { firstChildText(it, "developerConnection") },
                root?.let { firstChildText(it, "url") },
            )
        return candidates.firstNotNullOfOrNull { normalizeGitHubUrl(it) }
    }

    private fun fetchPomBody(
        group: String,
        artifact: String,
        version: String,
    ): String? {
        val path = "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"
        val response = safeGet(httpClient, "$baseUrl/$path") ?: return null
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
