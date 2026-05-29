plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.kformik.sample"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.kformik.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric needs this so resources resolve from the test classpath.
        }
    }
}

dependencies {
    implementation(project(":kformik"))
    implementation(project(":kformik-compose"))

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.foundation:foundation:1.7.5")

    // Robolectric-based Compose UI tests are gated behind `-PwithRobolectric=true` because
    // Robolectric's `nativeruntime-dist-compat` JAR is ~158 MB and is too large to fetch
    // reliably in restricted-network environments (timeouts in this repo's CI / sandbox).
    // To enable locally with full network access:
    //   ./gradlew :sample-android-app:testDebugUnitTest -PwithRobolectric=true
    if ((project.findProperty("withRobolectric") as? String) == "true") {
        testImplementation("org.robolectric:robolectric:4.13")
        testImplementation("androidx.test:core:1.6.1")
        testImplementation("androidx.test.ext:junit:1.2.1")
        testImplementation("androidx.compose.ui:ui-test-junit4:1.7.5")
        testImplementation("androidx.compose.ui:ui-test-manifest:1.7.5")
        testImplementation("junit:junit:4.13.2")
    }
}

// The Robolectric test sources live in `src/robolectricTest/` and are only added to the test
// source set when the property gate is on. This keeps the default `:testDebugUnitTest` task
// clean and dependency-free.
if ((project.findProperty("withRobolectric") as? String) == "true") {
    android.sourceSets["test"].kotlin.srcDir("src/robolectricTest/kotlin")
}
