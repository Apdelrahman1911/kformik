package io.kformik.sample.forms

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.kformik.sample.forms.screens.CustomAndAsyncScreen
import io.kformik.sample.forms.screens.LoginScreen
import io.kformik.sample.forms.screens.ProfileScreen
import io.kformik.sample.forms.screens.SignupScreen

internal data class Demo(val title: String, val screen: @Composable (() -> Unit) -> Unit)

internal val DEMOS: List<Demo> = listOf(
    Demo("Login") { back -> LoginScreen(back) },
    Demo("Signup — cross-field validation") { back -> SignupScreen(back) },
    Demo("Profile — Select / Radio / Multiline / reinitialize") { back -> ProfileScreen(back) },
    Demo("Custom + Async — renderOverride + validateAsync") { back -> CustomAndAsyncScreen(back) },
)

@Composable
fun App() {
    var picked by remember { mutableStateOf<Demo?>(null) }
    MaterialTheme {
        Surface {
            val current = picked
            if (current == null) HomeScreen(onPick = { picked = it })
            else current.screen { picked = null }
        }
    }
}
