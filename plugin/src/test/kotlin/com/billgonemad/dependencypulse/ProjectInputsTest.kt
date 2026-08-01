package com.billgonemad.dependencypulse

import org.gradle.testfixtures.ProjectBuilder
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectInputsTest {
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
}
