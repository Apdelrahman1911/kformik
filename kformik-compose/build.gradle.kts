plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

// group + version inherited from the root project (see root build.gradle.kts).

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components.findByName("release"))
                groupId = rootProject.group.toString()
                artifactId = "kformik-compose"
                version = rootProject.version.toString()
                artifact(tasks.register<Jar>("releaseJavadocJar") {
                    dependsOn("dokkaHtml")
                    archiveClassifier.set("javadoc")
                    from(layout.buildDirectory.dir("dokka/html"))
                })
                pom {
                    name.set("Kformik Compose")
                    description.set("Jetpack Compose adapter for Kformik — bind form state into Composables.")
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
            sign(publishing.publications["release"])
        }
    }

    tasks.withType<AbstractPublishToMaven>().configureEach {
        mustRunAfter(tasks.withType<Sign>())
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
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":kformik"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Compose runtime (no UI deps — adapter is just state + remember helpers).
    implementation("androidx.compose.runtime:runtime:1.7.5")
    implementation("androidx.compose.runtime:runtime-saveable:1.7.5")

    // Unit-test deps (run via :kformik-compose:testReleaseUnitTest)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
