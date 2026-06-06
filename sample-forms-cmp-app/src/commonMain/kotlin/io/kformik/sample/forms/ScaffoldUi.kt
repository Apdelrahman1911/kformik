package io.kformik.sample.forms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShowcaseScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        // Tap-outside-to-dismiss via Modifier.clickable on a wrapper Box.
        //
        //   - `Modifier.clickable` is Compose Foundation's standard click handler. It uses
        //     `awaitFirstDown(requireUnconsumed = true)` internally, so it only fires when
        //     no descendant consumed the press first. Descendants (Button / Slider /
        //     TextField / Checkbox / dropdown / Date picker / submit button) consume their
        //     own taps and keep working normally.
        //   - `indication = null` + a remembered `MutableInteractionSource` make the
        //     wrapper visually transparent — no ripple/highlight on tap, no visual
        //     interference with the form.
        //
        // Why not the Spacer-behind sibling pattern: on Compose Multiplatform iOS, Box
        // siblings do not reliably fall through hit-testing when the top sibling has a
        // pointer-input modifier (like `verticalScroll`) — the Column claims the touch
        // even when no specific child does, and the sibling Spacer behind it never sees
        // empty-space taps. Modifier.clickable on a parent Box has clean parent-child
        // semantics in Compose: children claim first, parent only sees unclaimed events.
        //
        // Why clearFocus() WITHOUT force = true: on Compose Multiplatform iOS (1.7.x),
        // force = true bypasses the cooperative-release path and leaves the focus tree
        // pinned to the root, after which no component can re-acquire focus.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusManager.clearFocus() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                content = content,
            )
        }
    }
}
