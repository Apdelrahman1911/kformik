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

    // Strict explicit-API mode: every public declaration must carry an explicit
    // `public` / `internal` / `private` modifier. Locks the discipline so future contributors
    // can't accidentally leak an implicit-public into the API surface.
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
            api(project(":kformik"))
            implementation(compose.runtime)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
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
            // Provides setContent / onNodeWithTag / assertions etc. for live @Composable surfaces
            // (state, dirty, isValid, fieldState, enableReinitialize).
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.material3)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "io.kformik.compose"
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
            name.set("Kformik Compose")
            description.set("Compose Multiplatform adapter for Kformik — bind form state into shared Composable code (Android, Desktop JVM, iOS).")
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
