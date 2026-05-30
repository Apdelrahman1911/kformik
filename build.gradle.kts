plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("com.android.library") version "8.5.2" apply false
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}

// ----- centralized group + version -----
// Modules don't set their own group/version; they inherit from these properties (defined in
// gradle.properties). One file to edit when cutting a release. Fail fast if a key is missing
// rather than silently publishing stale coordinates.
group = providers.gradleProperty("kformikGroup").get()
version = providers.gradleProperty("kformikVersion").get()

// ----- ABI guardrail: Binary Compatibility Validator -----
// `./gradlew apiDump` regenerates the committed `<module>/api/*.api` baselines; `apiCheck`
// (wired into `check`) fails the build on an unintended public-ABI change. Non-published
// demo modules are excluded.
apiValidation {
    ignoredProjects += listOf("examples", "sample-android-app")
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}

// ----- Maven Central / Sonatype OSSRH staging API publication target -----
// Opt-in: external publishing is only triggered when you explicitly invoke `publishToSonatype`
// / `closeAndReleaseSonatypeStagingRepository`. Credentials read from environment variables or
// user-scoped Gradle properties (~/.gradle/gradle.properties) — *no secrets in this repo*.
//
// URLs point at the **OSSRH Staging API bridge** that fronts the new Central Publisher Portal.
// This bridge supports groups verified via GitHub identity (`io.github.<username>`), which is
// how `io.github.apdelrahman1911` was claimed.
//
//   nexus URL:    https://ossrh-staging-api.central.sonatype.com/service/local/
//   snapshot URL: https://central.sonatype.com/repository/maven-snapshots/
//
// See docs/RELEASE_PROCESS.md.
nexusPublishing {
    repositories {
        sonatype {
            stagingProfileId.set(providers.gradleProperty("sonatypeStagingProfileId").orElse(""))
            username.set(
                providers.gradleProperty("SONATYPE_USERNAME")
                    .orElse(providers.environmentVariable("SONATYPE_USERNAME"))
                    .orElse(providers.gradleProperty("sonatypeUsername"))
                    .orElse("")
            )
            password.set(
                providers.gradleProperty("SONATYPE_PASSWORD")
                    .orElse(providers.environmentVariable("SONATYPE_PASSWORD"))
                    .orElse(providers.gradleProperty("sonatypePassword"))
                    .orElse("")
            )
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
