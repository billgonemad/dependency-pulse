# dependency-pulse

[![CI](https://github.com/billgonemad/dependency-pulse/actions/workflows/ci.yml/badge.svg)](https://github.com/billgonemad/dependency-pulse/actions/workflows/ci.yml)

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

The plugin is not yet published to the Gradle Plugin Portal. Build and publish it locally first:

```bash
./gradlew :plugin:publishToMavenLocal
```

Then in your project's `settings.gradle.kts`, add the local Maven repository to the plugin resolution:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

And apply the plugin in your `build.gradle.kts`:

```kotlin
plugins {
    id("com.billgonemad.dependency-pulse") version "0.2.0-SNAPSHOT"
}
```

## Configuration

```kotlin
dependencyPulse {
    failOnRed = false          // fail the build if any RED dependency is found
    failOnError = false        // fail the build if Maven Central is unreachable
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
