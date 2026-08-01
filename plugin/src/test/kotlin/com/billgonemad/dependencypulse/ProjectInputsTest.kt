package com.billgonemad.dependencypulse

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectInputsTest {
    @field:TempDir
    lateinit var repoDir: File

    @field:TempDir
    lateinit var multiProjectDir: File

    private fun writeFixture(
        group: String,
        artifact: String,
        version: String,
    ) {
        val dir = File(repoDir, "${group.replace('.', '/')}/$artifact/$version")
        dir.mkdirs()
        File(dir, "$artifact-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent(),
        )
        File(dir, "$artifact-$version.jar").writeBytes(
            byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )
    }

    private fun writeBomFixture(
        group: String,
        artifact: String,
        version: String,
    ) {
        val dir = File(repoDir, "${group.replace('.', '/')}/$artifact/$version")
        dir.mkdirs()
        File(dir, "$artifact-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <packaging>pom</packaging>
            </project>
            """.trimIndent(),
        )
        // Deliberately no .jar — a BOM/platform POM has no artifact of its own.
    }

    @Test fun `repo list starts with pomBaseUrl followed by declared http repos in order`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = URI("https://repo.example.com/first") }
        project.repositories.maven { it.url = URI("https://repo.example.com/second") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(
            listOf(
                "https://repo1.maven.org/maven2",
                "https://repo.example.com/first",
                "https://repo.example.com/second",
            ),
            urls,
        )
    }

    @Test fun `dedupes a declared repo matching pomBaseUrl after trailing-slash trim`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = URI("https://repo1.maven.org/maven2/") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2"), urls)
    }

    @Test fun `excludes mavenLocal since it resolves to a file URI MavenMetadataClient cannot query`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.mavenLocal()
        project.repositories.maven { it.url = URI("https://repo.example.com") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2", "https://repo.example.com"), urls)
    }

    @Test fun `excludes non-Maven repository types`() {
        val project = ProjectBuilder.builder().build()
        project.repositories.ivy { it.url = URI("https://ivy.example.com") }

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2"), urls)
    }

    @Test fun `returns only pomBaseUrl when no repositories are declared`() {
        val project = ProjectBuilder.builder().build()

        val urls = buildRepoUrls("https://repo1.maven.org/maven2", project.repositories)

        assertEquals(listOf("https://repo1.maven.org/maven2"), urls)
    }

    @Test fun `resolves coordinates from a resolvable configuration`() {
        writeFixture("com.example", "foo", "1.0")
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = repoDir.toURI() }
        project.configurations.create("testConfig") { it.isCanBeConsumed = false }
        project.dependencies.add("testConfig", "com.example:foo:1.0")

        val coords = resolveCoordinates(project, emptyList())

        assertEquals(setOf(Coords("com.example", "foo", "1.0")), coords)
    }

    @Test fun `excludes coordinates from configurations named in ignoreConfigurations`() {
        writeFixture("com.example", "foo", "1.0")
        writeFixture("com.example", "bar", "1.0")
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = repoDir.toURI() }
        project.configurations.create("kept") { it.isCanBeConsumed = false }
        project.configurations.create("ignoredConfig") { it.isCanBeConsumed = false }
        project.dependencies.add("kept", "com.example:foo:1.0")
        project.dependencies.add("ignoredConfig", "com.example:bar:1.0")

        val coords = resolveCoordinates(project, listOf("ignoredConfig"))

        assertEquals(setOf(Coords("com.example", "foo", "1.0")), coords)
    }

    @Test fun `dedupes identical coordinates found in multiple configurations`() {
        writeFixture("com.example", "foo", "1.0")
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = repoDir.toURI() }
        project.configurations.create("first") { it.isCanBeConsumed = false }
        project.configurations.create("second") { it.isCanBeConsumed = false }
        project.dependencies.add("first", "com.example:foo:1.0")
        project.dependencies.add("second", "com.example:foo:1.0")

        val coords = resolveCoordinates(project, emptyList())

        assertEquals(setOf(Coords("com.example", "foo", "1.0")), coords)
    }

    @Test fun `excludes project dependencies since they are not published Maven coordinates`() {
        writeFixture("com.example", "foo", "1.0")
        val childDir = File(multiProjectDir, "child").apply { mkdirs() }
        val root = ProjectBuilder.builder().withProjectDir(multiProjectDir).withName("root").build()
        ProjectBuilder.builder().withProjectDir(childDir).withName("child").withParent(root).build()
        root.repositories.maven { it.url = repoDir.toURI() }
        root.configurations.create("testConfig") { it.isCanBeConsumed = false }
        root.dependencies.add("testConfig", "com.example:foo:1.0")
        root.dependencies.add("testConfig", root.dependencies.project(mapOf("path" to ":child")))

        val coords = resolveCoordinates(root, emptyList())

        assertEquals(setOf(Coords("com.example", "foo", "1.0")), coords)
    }

    @Test fun `includes BOM-platform components even though they have no jar artifact`() {
        writeFixture("com.example", "foo", "1.0")
        writeBomFixture("com.example", "bom", "1.0")
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.url = repoDir.toURI() }
        project.configurations.create("testConfig") { it.isCanBeConsumed = false }
        project.dependencies.add("testConfig", "com.example:foo:1.0")
        project.dependencies.add("testConfig", project.dependencies.platform("com.example:bom:1.0"))

        val coords = resolveCoordinates(project, emptyList())

        assertEquals(
            setOf(Coords("com.example", "foo", "1.0"), Coords("com.example", "bom", "1.0")),
            coords,
        )
    }
}
