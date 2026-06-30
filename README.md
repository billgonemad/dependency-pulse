# dependency-pulse

[![CI](https://github.com/billgonemad/dependency-pulse/actions/workflows/ci.yml/badge.svg)](https://github.com/billgonemad/dependency-pulse/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/billgonemad/dependency-pulse)](https://github.com/billgonemad/dependency-pulse/releases)

A Gradle plugin that checks how stale your JVM dependencies are.
It queries Maven Central for each dependency's latest release date and
classifies them as green (active), yellow (aging), or red (abandoned/gone).
Run it as part of your build or on demand to catch forgotten dependencies
before they become a maintenance problem.

## Sample Output

```
Dependency Pulse Report
=======================
✅ com.squareup.okhttp3:okhttp:4.12.0
   Latest: 4.12.0 | Released: 3 months ago | Active
⚠️  org.apache.commons:commons-lang3:3.12.0
   Latest: 3.14.0 | Released: 15 months ago
🔴 io.abandoned:legacy-lib:1.0.0
   Artifact no longer published to Maven Central
❓ com.flaky:unreachable-lib:2.0.0
   Maven Central unavailable — skipped (set failOnError=true to fail the build)
=======================
4 dependencies scanned. 1 red, 1 yellow, 1 green, 1 unknown.
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
```groovy
plugins {
    // x-release-please-start-version
    id 'com.billgonemad.dependency-pulse' version '0.2.0'
    // x-release-please-end-version
}
```

**`build.gradle.kts` (Kotlin)**
```kotlin
plugins {
    // x-release-please-start-version
    id("com.billgonemad.dependency-pulse") version "0.2.0"
    // x-release-please-end-version
}
```

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
    thresholds {
        yellowAfterMonths = 12  // months since last release before YELLOW
        redAfterMonths = 24     // months since last release before RED
    }
}
```

All fields are optional. The values shown above are the defaults.

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

For each resolved dependency, the plugin queries the Maven Central search API
for the artifact's latest release date. It then classifies the dependency:

| Status | Condition |
|--------|-----------|
| ✅ Green | Last release < 12 months ago |
| ⚠️ Yellow | Last release 12–24 months ago |
| 🔴 Red | Last release > 24 months ago, or artifact not found on Maven Central |
| ❓ Unknown | Maven Central could not be reached |

Thresholds are configurable. Test configurations are excluded by default.

## License

Apache 2.0 — see [LICENSE](LICENSE).
