plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

// group + version inherited from the root project (see root build.gradle.kts).

java {
    withSourcesJar()
}
tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaHtml")
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("dokka/html"))
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain(17)
}

// kctfork (kotlin-compile-testing) marks its API as experimental via
// `org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi`. The marker only exists on the
// test classpath (via kctfork's own deps), so scope the opt-in to test compilation only.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.0.21-1.0.27")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")

    // KSP compile-testing — runs the processor against in-memory Kotlin source files and lets
    // us assert the generated content. `kctfork` is the maintained fork of `kotlin-compile-testing`
    // by Zac Sweers; the `ksp` artifact wires up KSP 2.x.
    testImplementation("dev.zacsweers.kctfork:core:0.5.1")
    testImplementation("dev.zacsweers.kctfork:ksp:0.5.1")

    // Generated updaters reference io.kformik.ValuesUpdater (compile-testing sources need it on
    // the classpath too).
    testImplementation(project(":kformik"))
}

publishing {
    publications {
        create<MavenPublication>("kformik-ksp") {
            from(components["java"])
            artifact(tasks.named("javadocJar"))
            pom {
                name.set("Kformik KSP (experimental)")
                description.set("KSP processor that generates typed field paths + ValuesUpdater for @FormValues data classes.")
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
        sign(publishing.publications)
    }
}
