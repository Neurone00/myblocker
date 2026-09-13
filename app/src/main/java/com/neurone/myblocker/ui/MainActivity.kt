package com.neurone.myblocker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.neurone.myblocker.update.InstallReceiver
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) autoStart.value = true
        val showSplash = savedInstanceState == null && !splashShownThisProcess
        setContent {
            AdbrellaTheme {
                var splash by remember { mutableStateOf(showSplash) }
                Box(Modifier.fillMaxSize()) {
                    AppRoot()
                    if (splash) SplashOverlay { splash = false; splashShownThisProcess = true }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) autoStart.value = true
    }

    override fun onResume() {
        super.onResume()
        InstallReceiver.foreground = true
    }

    override fun onPause() {
        InstallReceiver.foreground = false
        super.onPause()
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"

        /** Set by the Quick Settings tile when consent is still needed; the home screen consumes it. */
        val autoStart = MutableStateFlow(false)
        private var splashShownThisProcess = false
    }
}
