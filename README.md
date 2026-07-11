# dependency-pulse

[![CI](https://github.com/billgonemad/dependency-pulse/actions/workflows/ci.yml/badge.svg)](https://github.com/billgonemad/dependency-pulse/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/billgonemad/dependency-pulse)](https://github.com/billgonemad/dependency-pulse/releases)

A Gradle plugin that checks how stale your JVM dependencies are.
It queries Maven Central for each dependency's latest release date, and — when
the dependency's GitHub repository can be resolved — also checks whether that
repo is archived or has gone quiet. It classifies each dependency as green
(active), yellow (aging), or red (abandoned/gone). Run it as part of your
build or on demand to catch forgotten dependencies before they become a
maintenance problem.

## Sample Output

```
Dependency Pulse Report
=======================
✅ com.squareup.okhttp3:okhttp:4.12.0
   Latest: 4.12.0 | Released: 3 months ago | Active
⚠️  org.apache.commons:commons-lang3:3.12.0
   Latest: 3.14.0 | Released: 15 months ago
   GitHub: Last commit 14 months ago
🔴 io.abandoned:legacy-lib:1.0.0
   Artifact no longer published to Maven Central
   GitHub: Repo archived
📘 jakarta.annotation:jakarta.annotation-api:3.0.0
   Spec (stable) | Latest: 3.0.0 | Released: 29 months ago
❓ com.flaky:unreachable-lib:2.0.0
   Maven Central unavailable — skipped (set failOnError=true to fail the build)
=======================
5 dependencies scanned. 1 red, 1 yellow, 1 green, 1 unknown, 1 stable.
```

## Requirements

- Java 17+
- Gradle 7.6+

## Installation

The plugin is published to [GitHub Packages](https://github.com/billgonemad/dependency-pulse/packages).
You need a GitHub token with `read:packages` scope — either `GITHUB_TOKEN` in CI or a
[personal access token](https://github.com/settings/tokens) locally.

**`settings.gradle` (Groovy)**
```groovy
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/billgonemad/dependency-pulse")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user")
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key")
            }
        }
        gradlePluginPortal()
    }
}
```

**`settings.gradle.kts` (Kotlin)**
```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/billgonemad/dependency-pulse")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
            }
        }
        gradlePluginPortal()
    }
}
```

Then apply the plugin:

**`build.gradle` (Groovy)**
<!-- x-release-please-start-version -->
```groovy
plugins {
    id 'com.billgonemad.dependency-pulse' version '0.5.2'
}
```
<!-- x-release-please-end-version -->

**`build.gradle.kts` (Kotlin)**
<!-- x-release-please-start-version -->
```kotlin
plugins {
    id("com.billgonemad.dependency-pulse") version "0.5.2"
}
```
<!-- x-release-please-end-version -->

> Publishing to the Gradle Plugin Portal is planned — once on the Portal, no repository
> configuration or credentials will be required.

## Configuration

```kotlin
dependencyPulse {
    failOnRed = false          // fail the build if any RED dependency is found
    failOnError = false        // fail the build if Maven Central is unreachable
    runOnCheck = false         // attach dependencyPulse to the check lifecycle task
    ignoreConfigurations = listOf(
        "testImplementation",
        "testRuntimeOnly",
        "testCompileClasspath",
        "testRuntimeClasspath",
    )
    knownStableGroups = listOf("jakarta.", "javax.")  // spec/API artifacts exempt from staleness scoring
    githubToken = null          // GitHub token to raise the API rate limit (60/hr -> 5,000/hr)
    thresholds {
        yellowAfterMonths = 12  // months since last release before YELLOW
        redAfterMonths = 24     // months since last release before RED
    }
}
```

All fields are optional. The values shown above are the defaults.

`githubToken` is only needed if you're scanning enough dependencies to hit GitHub's
unauthenticated rate limit (60 requests/hour). A token with no special scopes —
just read access to public repos — is enough:

```kotlin
dependencyPulse {
    githubToken = System.getenv("GITHUB_TOKEN") // or providers.gradleProperty("githubToken")
}
```

Run the task with:

```bash
./gradlew dependencyPulse
```

### Failing CI on abandoned dependencies

To gate your CI pipeline on dependency health, combine `runOnCheck` with `failOnRed`:

```kotlin
dependencyPulse {
    runOnCheck = true   // run automatically with ./gradlew check
    failOnRed = true    // fail the build if any dependency is RED (abandoned)
}
```

`runOnCheck` controls whether the task runs during `check`. `failOnRed` controls whether a stale dependency actually breaks the build. Both must be `true` to gate CI.

## How it works

For each resolved dependency, the plugin queries Maven Central for the
artifact's version metadata and latest release date. It then classifies the
dependency:

| Status | Condition |
|--------|-----------|
| ✅ Green | Last release < 12 months ago |
| ⚠️ Yellow | Last release 12–24 months ago |
| 🔴 Red | Last release > 24 months ago, or artifact not found on Maven Central |
| ❓ Unknown | Maven Central could not be reached |
| 📘 Spec (stable) | Matches `knownStableGroups` and real Maven data exists — shown instead of the status above, regardless of age |

Thresholds are configurable. Test configurations are excluded by default.

### GitHub signals

When a dependency's POM links to a GitHub repository, the plugin also checks
that repo's health and folds it into the same status — whichever of the Maven
Central result and the GitHub result is worse wins:

- An **archived** repository always forces 🔴 Red, regardless of how recently
  the artifact itself was released.
- A **stale last commit** (older than the same `yellowAfterMonths` /
  `redAfterMonths` thresholds used for Maven releases) can downgrade an
  otherwise-Green dependency to Yellow or Red.
- If the repo can't be resolved, GitHub rate-limits the request, or the
  lookup fails, the dependency's status is unaffected — it's scored on Maven
  Central data alone, exactly as if GitHub signals weren't checked at all.

### Known-stable dependencies

Some artifacts — spec/API jars like Jakarta EE and legacy `javax.*` packages —
are versioned per specification revision and can legitimately go years
between releases without being abandoned. `knownStableGroups` marks these so
they're never scored as abandoned, while still showing their real release
date:

````
```
📘 jakarta.annotation:jakarta.annotation-api:3.0.0
   Spec (stable) | Latest: 3.0.0 | Released: 29 months ago
```
````

Matched dependencies are excluded from `failOnRed` and counted separately in
the summary. This only applies when the plugin has real Maven data for the
dependency — if the artifact can't be reached or has been removed from
Maven Central, it's still reported and gated normally; there's no "stable"
label to hide behind for a genuine error.

Entries can be a group-ID prefix (`'jakarta.'`, matched against
`group.startsWith(...)`) or an exact `group:artifact` coordinate
(`'com.google.code.findbugs:jsr305'`) for a specific artifact within an
otherwise actively-developed group. The defaults are `['jakarta.', 'javax.']`.

## License

Apache 2.0 — see [LICENSE](LICENSE).
