package com.billgonemad.dependencypulse

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.gradle.testkit.runner.GradleRunner
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal const val HTTP_404 = 404

internal const val TAKE_REQUEST_TIMEOUT_SECONDS = 5L

// Standard Maven layout: .../{group-with-slashes}/{artifactId}/{version}/{artifactId}-{version}.pom
// — counting back from the end of the split path, version is 2nd-to-last, artifactId 3rd-to-last.
private const val VERSION_SEGMENT_FROM_END = 2

private const val ARTIFACT_ID_SEGMENT_FROM_END = 3

// Minimal valid empty ZIP (End Of Central Directory record, zero entries) — enough for
// Gradle's lenient artifact resolution to accept a .jar response; fixtures declare no
// .java source, so nothing ever compiles against the jar's actual bytecode contents.
@Suppress("MagicNumber")
private val EMPTY_ZIP_BYTES =
    byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

internal fun mavenDispatcher(
    latestVersion: String,
    lastModifiedEpochMs: Long,
    scmUrl: String? = null,
    // false for a "healthy" repo that exists solely so Gradle's own dependency resolution
    // doesn't drop the coordinate (see the failOn* tests in DependencyPulsePluginFunctionalTest)
    // but must NOT itself supply real Maven signals to the plugin's walk loop — Gradle never
    // needs maven-metadata.xml to resolve a fixed (non-range, non-SNAPSHOT) version, so 404-ing
    // it here doesn't affect Gradle's resolution, only the plugin's own fetchSignals call
    // against this repo.
    serveMetadata: Boolean = true,
    // When set, 404s the POM for exactly this version (while every other coordinate's POM,
    // and this same version's .jar, still serve normally) — simulates a metadata-selected
    // "latest" version whose POM is itself unusable, without needing a bespoke dispatcher.
    pomNotFoundForVersion: String? = null,
): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val pomSegments = path.removePrefix("/").split("/")
            return when {
                path.endsWith("maven-metadata.xml") && serveMetadata -> {
                    MockResponse()
                        .setBody(
                            "<metadata><versioning><latest>$latestVersion</latest>" +
                                "<versions><version>$latestVersion</version></versions></versioning></metadata>",
                        ).setHeader("Connection", "close")
                }

                path.endsWith(".jar") -> {
                    MockResponse().setBody(Buffer().write(EMPTY_ZIP_BYTES)).setHeader("Connection", "close")
                }

                path.endsWith(".pom") &&
                    pomNotFoundForVersion != null &&
                    pomSegments[pomSegments.size - VERSION_SEGMENT_FROM_END] == pomNotFoundForVersion -> {
                    MockResponse().setResponseCode(HTTP_404)
                }

                path.endsWith(".pom") -> {
                    val scmFragment = scmUrl?.let { "<scm><url>$it</url></scm>" }.orEmpty()
                    // Gradle's real POM resolver requires groupId/artifactId/version to be
                    // present AND to match the requested coordinate exactly (verified: a
                    // mismatch fails with "inconsistent module metadata found", not just a
                    // parse error) — derived here from the standard Maven layout path
                    // (/{group-with-slashes}/{artifactId}/{version}/{artifactId}-{version}.pom)
                    // rather than hardcoded, so this dispatcher works for any coordinate a
                    // test declares without adding parameters.
                    val version = pomSegments[pomSegments.size - VERSION_SEGMENT_FROM_END]
                    val artifactId = pomSegments[pomSegments.size - ARTIFACT_ID_SEGMENT_FROM_END]
                    val groupId =
                        pomSegments.subList(0, pomSegments.size - ARTIFACT_ID_SEGMENT_FROM_END).joinToString(".")
                    MockResponse()
                        .setBody(
                            "<project><groupId>$groupId</groupId><artifactId>$artifactId</artifactId>" +
                                "<version>$version</version>$scmFragment</project>",
                        ).setHeader("Connection", "close")
                        .apply {
                            // A repo with no maven-metadata.xml (serveMetadata = false) must also
                            // withhold the currentVersion POM fallback signal
                            // MavenMetadataClient.fetchLastModified probes for: that fallback
                            // already treats a 200-OK POM response with no Last-Modified header as
                            // "no signal" (returns null), so omitting the header here — regardless
                            // of HTTP method or caller — reuses that existing logic instead of
                            // needing to distinguish requests.
                            if (serveMetadata) {
                                setHeader(
                                    "Last-Modified",
                                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                        Instant.ofEpochMilli(lastModifiedEpochMs).atZone(ZoneOffset.UTC),
                                    ),
                                )
                            }
                        }
                }

                // Gradle probes for .module (Gradle Module Metadata) and .sha1/.md5 checksum
                // files before falling back to the .pom alone; a clean 404 here is what makes
                // it fall back cleanly. Serving 200+XML for these (as an earlier version of
                // this dispatcher did) makes Gradle try to parse the response as real module
                // metadata, fail, and silently drop the dependency via lenient resolution —
                // this was verified in a spike; a real repository behaves the same way.
                else -> {
                    MockResponse().setResponseCode(HTTP_404)
                }
            }
        }
    }

internal fun githubDispatcher(pushedAt: String): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.path?.endsWith("/commits?per_page=1") == true) {
                MockResponse().setBody("""[{"commit":{"committer":{"date":"$pushedAt"}}}]""")
            } else {
                MockResponse().setBody("""{"archived":false,"pushed_at":"$pushedAt"}""")
            }
    }

// Every fixture's repositories {} block points here — real jar resolution and the plugin's
// own metadata queries hit the same mock server, so no fixture ever touches the real internet.
internal fun MockWebServer.repositoriesBlock(): String =
    """
    repositories {
        maven {
            url = uri("http://$hostName:$port")
            allowInsecureProtocol = true
        }
    }
    """.trimIndent()

internal fun GradleRunner.withCompatGradleVersion(): GradleRunner {
    val version = System.getProperty("testGradleVersion")?.takeIf { it.isNotBlank() }
    return if (version != null) withGradleVersion(version) else this
}
