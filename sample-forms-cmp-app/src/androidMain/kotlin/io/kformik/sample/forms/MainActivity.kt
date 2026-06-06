package io.kformik.sample.forms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
