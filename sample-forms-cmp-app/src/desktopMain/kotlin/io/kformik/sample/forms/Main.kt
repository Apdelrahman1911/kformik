package io.kformik.sample.forms

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

public fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kformik Forms Showcase",
            state = rememberWindowState(width = 480.dp, height = 900.dp),
        ) {
            App()
        }
    }
}
