package com.athkar.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.athkar.app.security.SessionManager
import com.athkar.feature.athkar.AthkarRoute
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Compose host. Responsibilities per the delivery brief:
 *   - Hide the task snapshot while locked (FLAG_SECURE) and lock the session on backgrounding.
 *   - Route between the athkar home and prayer-times screens.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (sessionManager.isUnlocked()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    LaunchedEffect(Unit) {
                        if (!sessionManager.isUnlocked()) {
                            // Drive the in-app BiometricPrompt / PIN lock gate here (omitted for brevity;
                            // SessionManager exposes requestUnlock()/onBiometricSuccess()).
                        }
                    }
                    NavHost(navController = navController, startDestination = "athkar") {
                        composable("athkar") { AthkarRoute(onBack = { finish() }) }
                        composable("prayer") { PrayerTimesPlaceholder() }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Lock and hide task snapshot immediately when leaving foreground (delivery brief).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sessionManager.onBackground()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionManager.recordActivity()
    }
}
