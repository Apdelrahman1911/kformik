package io.kformik.sample.forms

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Exposes the showcase's Compose UI as a [UIViewController] so the bundled SwiftUI host
 * (`sample-forms-cmp-app/iosApp/`) can embed it via `UIViewControllerRepresentable`.
 *
 * The framework binary is built via `./gradlew :sample-forms-cmp-app:linkDebugFrameworkIosSimulatorArm64`
 * and lands at `sample-forms-cmp-app/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework`.
 */
public fun MainViewController(): UIViewController = ComposeUIViewController { App() }
