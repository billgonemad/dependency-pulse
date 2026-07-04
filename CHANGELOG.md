# Changelog

## [0.4.0](https://github.com/billgonemad/dependency-pulse/compare/v0.3.0...v0.4.0) (2026-07-04)


### Features

* **github-signals:** extract GitHub SCM URL from dependency POMs ([e471a8e](https://github.com/billgonemad/dependency-pulse/commit/e471a8e68539ceab8ea234310d4aad5bfdb0622e))
* **github-signals:** GitHub API client + populate GitHubSignals ([7d96d73](https://github.com/billgonemad/dependency-pulse/commit/7d96d734964d5b1e9de61c49058d05ff708e9918))


### Bug Fixes

* **maven-central:** switch default host from search.maven.org to central.sonatype.com ([57bba9b](https://github.com/billgonemad/dependency-pulse/commit/57bba9b48c14f416d30e1a50f03f465f38c25a1d))
* **release:** remove redundant release-type from workflow so extra-files are applied ([b2a61a2](https://github.com/billgonemad/dependency-pulse/commit/b2a61a2d0154dd4637d5c133e0f1cc3112218d70))
* **release:** rename config file to match release-please-action default path ([0f218ff](https://github.com/billgonemad/dependency-pulse/commit/0f218fff36d91d7ba6c932bd6dbcb52e1a849198))

## [0.3.0](https://github.com/billgonemad/dependency-pulse/compare/v0.2.0...v0.3.0) (2026-06-30)


### Features

* **plugin:** add runOnCheck flag to wire dependencyPulse into check ([827f290](https://github.com/billgonemad/dependency-pulse/commit/827f290b0537efb99c7e2e72b73c9dac90adaa95))

## [0.2.0](https://github.com/billgonemad/dependency-pulse/compare/v0.1.0...v0.2.0) (2026-06-27)


### Features

* add VersionSelector for stable-version selection ([9c3cd5e](https://github.com/billgonemad/dependency-pulse/commit/9c3cd5e6051140a2c93b2b593503679f7c8ac055))
* enumerate versions via core=gav and select latest stable ([0065075](https://github.com/billgonemad/dependency-pulse/commit/0065075c4f498ec4a6ce8e7af74b3df891a4aa6d))
* pass consumer version to Maven Central lookup ([f44d3da](https://github.com/billgonemad/dependency-pulse/commit/f44d3dad2989a48d67656f8dec6bcaa24297af7c))


### Bug Fixes

* select latest stable version, ignoring pre-releases ([#4](https://github.com/billgonemad/dependency-pulse/issues/4)) ([b55376c](https://github.com/billgonemad/dependency-pulse/commit/b55376c05ffdd46b293f7e219beedefad434bff2))

## [0.1.0](https://github.com/billgonemad/dependency-pulse/compare/v0.0.0...v0.1.0) (2026-06-24)


### Features

* **analyzer:** add DependencyAnalyzer with injectable resolver ([1226af4](https://github.com/billgonemad/dependency-pulse/commit/1226af424cacb32ed0281e4ef6d5a34e94f57c6e))
* **client:** add MavenCentralClient with two-call Maven Central strategy ([e23001c](https://github.com/billgonemad/dependency-pulse/commit/e23001c83126e8760b0bd7c7fefdb23531e25a59))
* **client:** cache URL responses within a single task run ([83ec2c5](https://github.com/billgonemad/dependency-pulse/commit/83ec2c5a1a524d33ed93782fc8f1bf9a017e5f39))
* **client:** retry on 429/5xx with exponential back-off ([2c895ab](https://github.com/billgonemad/dependency-pulse/commit/2c895ab0803b1c232af00c9c8a119389412c8f2c)), closes [#3](https://github.com/billgonemad/dependency-pulse/issues/3)
* **domain:** add DependencyInfo model and score() function ([cef3937](https://github.com/billgonemad/dependency-pulse/commit/cef3937e5bed29beb9318b925b0a50b598545f5d))
* **phase1:** Maven Central health-check MVP ([6c3fba7](https://github.com/billgonemad/dependency-pulse/commit/6c3fba7335c15298c4ca94a23c773a811e35154e))
* **plugin:** wire DependencyPulsePlugin with extension DSL and task skeleton ([b1f20a8](https://github.com/billgonemad/dependency-pulse/commit/b1f20a8a69d578e21a141b2fc37ab44d88ea0c16))
* **report:** add ReportPrinter console output ([5f36b49](https://github.com/billgonemad/dependency-pulse/commit/5f36b496b269edbd003fcec359305637ec525173))
* **task:** wire DependencyPulseTask @TaskAction ([e0e790b](https://github.com/billgonemad/dependency-pulse/commit/e0e790bb15e867b26b461d86a75788c5352570a4))


### Bug Fixes

* **ci:** address post-review findings in guardrail workflows ([9a937f1](https://github.com/billgonemad/dependency-pulse/commit/9a937f10a4e5f8b007ef9a6d4cc31faa49f2b1fa))
* **client:** extract HTTP status codes to named constants ([84b60cc](https://github.com/billgonemad/dependency-pulse/commit/84b60cc72c88aa8f730cf7d780e3c0f4c5f8b7ea))
* **client:** resolve Maven Central rate-limiting causing non-deterministic scan results ([5d11a93](https://github.com/billgonemad/dependency-pulse/commit/5d11a9309d92f599b431176521a0942a2d8984d2))
* **plugin:** remove afterEvaluate, adopt Gradle lazy configuration (issue [#6](https://github.com/billgonemad/dependency-pulse/issues/6)) ([5504dc9](https://github.com/billgonemad/dependency-pulse/commit/5504dc98373493b8a9893e79a0ab7c18f3ff03b0))
* **plugin:** remove afterEvaluate, adopt lazy task configuration for Gradle 9 ([ca31319](https://github.com/billgonemad/dependency-pulse/commit/ca313196dc9424a0d6c18479afaecb6f52d4ae5d))
* **plugin:** use providers.systemProperty for mavenCentralBaseUrl to support config cache ([8a33e70](https://github.com/billgonemad/dependency-pulse/commit/8a33e700969e25c9e2bdb7cf53df333b6242c106))
* **plugin:** wire githubToken lazily and strengthen extension tests ([d232227](https://github.com/billgonemad/dependency-pulse/commit/d232227d255796f8da5adbc975d9cda4af9b9e1b))
* **report:** explicit RED guard and injectable clock in ReportPrinter ([1f54801](https://github.com/billgonemad/dependency-pulse/commit/1f54801a0c95230bba56595cca1d4b94d5d4c278))
* **report:** remove trailing space from YELLOW emoji mapping ([17ee456](https://github.com/billgonemad/dependency-pulse/commit/17ee456aa88589d0d89fb730f490a3d51ed45fb3))
* **review:** address code review findings ([6465c5c](https://github.com/billgonemad/dependency-pulse/commit/6465c5c3af771e8285a6df9ae0e455110f2203de))
* **security:** bump OWASP DC to 12.2.2 and add OSS Index credentials ([4172f80](https://github.com/billgonemad/dependency-pulse/commit/4172f80e71238631d9daf07992a819a0b9a101d6))
* **security:** increase heap to 2g for OWASP NVD database processing ([3d27b97](https://github.com/billgonemad/dependency-pulse/commit/3d27b973321637aac481a312c847322869d1eb79))
* **security:** remove pull_request trigger from OWASP scan ([29c0921](https://github.com/billgonemad/dependency-pulse/commit/29c0921db7d94d02d28be461f2a05043626f0d8a))
* **security:** remove pull_request trigger from OWASP scan ([760678c](https://github.com/billgonemad/dependency-pulse/commit/760678c8f970aae6678b5b423ae1b77b9c2508da))
* **security:** set org.gradle.jvmargs to fix GC thrashing on OWASP scan ([1873675](https://github.com/billgonemad/dependency-pulse/commit/1873675897ed514e7e9b4d126b29f64edc35b441))
* **task:** add retryDelayMs system property and clean up functional test ([e743308](https://github.com/billgonemad/dependency-pulse/commit/e74330881d803f6eb6295c931fcb54feecfcaca4))
