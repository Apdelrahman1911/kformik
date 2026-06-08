plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

// group + version inherited from the root project (see root build.gradle.kts).

kotlin {
    jvmToolchain(17)

    // Strict explicit-API mode: every public declaration in commonMain must carry an explicit
    // `public` / `internal` / `private` modifier. This module was written explicit-public from
    // day one (v1.8.0), so flipping the mode produces no diff — but it locks the discipline in
    // so future contributors can't accidentally leak an implicit-public into the API surface.
    // Other published modules (`:kformik`, `:kformik-compose`, `:kformik-ksp`) use implicit
    // visibility; their migration to explicitApi() is a follow-up task — see CHANGELOG.
    explicitApi()

    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api` because consumers will hold ComposeFormik<Map<String, Any?>> from this module's
            // public composables (KformikForm passes it down to render-override callbacks).
            api(project(":kformik-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            // Date renderer formats epoch-millis (from DatePickerState) ↔ ISO yyyy-MM-dd strings.
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation("junit:junit:4.13.2")
            // Compose Multiplatform's headless UI test harness — `runComposeUiTest { ... }`
            // works on JVM via a Swing-based renderer; no display server / xvfb required.
            // Lets the renderer + KformikForm integration tests drive setContent / onNodeWith… /
            // perform… on the same Material 3 surface consumers ship.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "io.kformik.forms"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Apply POM metadata + signing to every KMP publication (the maven-publish + kotlin("multiplatform")
// combination creates one publication per target: `kotlinMultiplatform` + `androidRelease` + `jvm`
// + `iosX64` + `iosArm64` + `iosSimulatorArm64`).
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(
            tasks.register<Jar>("${name}DokkaJar") {
                dependsOn("dokkaHtml")
                archiveClassifier.set("javadoc")
                archiveAppendix.set(this@configureEach.name)
                from(layout.buildDirectory.dir("dokka/html"))
            }
        )
        pom {
            name.set("Kformik Forms")
            description.set("Declarative form layer for Kformik — describe a form as Map<String, Field> and render it via Compose Multiplatform Material 3 widgets.")
            url.set("https://github.com/Apdelrahman1911/kformik")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("apdelrahman1911")
                    name.set("Abdelrahman Fahmy")
                    email.set("abdelrahmanfahmy.dev@gmail.com")
                }
            }
            scm {
                url.set("https://github.com/Apdelrahman1911/kformik")
                connection.set("scm:git:https://github.com/Apdelrahman1911/kformik.git")
                developerConnection.set("scm:git:ssh://git@github.com/Apdelrahman1911/kformik.git")
            }
        }
    }
}

signing {
    // Credentials read from env vars OR user-scoped ~/.gradle/gradle.properties — never the repo.
    val signingKey: String? = (findProperty("SIGNING_KEY") as? String)
        ?: System.getenv("SIGNING_KEY")
        ?: (findProperty("signingKey") as? String)
    val signingPassword: String? = (findProperty("SIGNING_PASSWORD") as? String)
        ?: System.getenv("SIGNING_PASSWORD")
        ?: (findProperty("signingPassword") as? String)
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// Gradle 8.x requires explicit ordering between Sign tasks and the AbstractPublishToMaven tasks
// that consume their .asc outputs across publications.
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
