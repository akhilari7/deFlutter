package com.payatu.flutterinspect

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.payatu.flutterinspect.server.WebTerminalServer
import com.payatu.flutterinspect.ui.screens.MainScreen
import com.payatu.flutterinspect.ui.theme.FlutterInspectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWebServerIntent(intent)

        setContent {
            FlutterInspectTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWebServerIntent(intent)
    }

    private fun handleWebServerIntent(intent: Intent?) {
        val prefs = getSharedPreferences("flutterinspect_prefs", Context.MODE_PRIVATE)
        val forceStart = intent?.getBooleanExtra("start_web_server", false) ?: false
        val forceStop = intent?.getBooleanExtra("stop_web_server", false) ?: false

        if (forceStop) {
            WebTerminalServer.stop()
            prefs.edit().putBoolean("web_server_enabled", false).apply()
        } else if (forceStart || prefs.getBoolean("web_server_enabled", false)) {
            WebTerminalServer.start(applicationContext)
            if (forceStart) {
                prefs.edit().putBoolean("web_server_enabled", true).apply()
            }
        }
    }
}
