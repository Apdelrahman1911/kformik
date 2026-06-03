plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

// group + version inherited from the root project (see root build.gradle.kts).

// One Dokka HTML output → one shared Jar that every KMP publication attaches as the javadoc
// classifier. Dokka 1.9.x handles KMP source sets out of the box.
val dokkaHtmlJar = tasks.register<Jar>("dokkaHtmlJar") {
    dependsOn("dokkaHtml")
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("dokka/html"))
}

publishing {
    publications.withType<MavenPublication> {
        // Attach the Dokka-generated docs jar to every publication (replaces the empty
        // placeholder used through v1.3.x). Maven Central requires a javadoc classifier.
        artifact(dokkaHtmlJar)

        pom {
            name.set("Kformik")
            description.set("Kotlin Multiplatform port of Formik — UI-independent form state for Android, iOS, and JVM.")
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

// Gradle 8.x requires explicit ordering between Sign tasks and any other task that reads from
// directories the Sign tasks decorate with .asc artifacts. This covers:
//  - publish tasks (consume <artifact>.asc files via the publication)
//  - Kotlin/Native test compile tasks (their input directory contains the .klib that gets a
//    sibling .klib.asc written by the Sign task)
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
tasks.matching { it.name.startsWith("compileTestKotlin") }.configureEach {
    mustRunAfter(tasks.withType<Sign>())
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(17)

    // Strict explicit-API mode: every public declaration must carry an explicit
    // `public` / `internal` / `private` modifier. Locks the discipline so future contributors
    // can't accidentally leak an implicit-public into the API surface; the v1.9.0 rollout
    // surfaced (and corrected) several implicit-public declarations.
    explicitApi()

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
        publishLibraryVariants("release")
    }

    // Declare a framework binary on each iOS target so the documented
    // `linkReleaseFrameworkIos*` / `linkDebugFrameworkIos*` tasks actually exist and consumers can
    // add the produced Kformik.framework to Xcode (see docs/IOS_USAGE.md).
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Kformik"
        }
    }

    // Intermediate iosMain / iosTest source sets shared by all three iOS targets.
    @Suppress("UNUSED_VARIABLE")
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:5.10.3")
        }

        // iosTest is created automatically by applyDefaultHierarchyTemplate().
        // The dependency block ensures it inherits commonTest dependencies (kotlin.test,
        // kotlinx-coroutines-test) for the iOS targets.
    }
}

android {
    namespace = "io.kformik"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
