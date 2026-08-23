package io.github.prince_dulb.ohmynotification

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.prince_dulb.ohmynotification.ui.PhaseZeroScreen
import io.github.prince_dulb.ohmynotification.ui.theme.OhMyNotificationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OhMyNotificationTheme {
                PhaseZeroScreen()
            }
        }
    }
}
