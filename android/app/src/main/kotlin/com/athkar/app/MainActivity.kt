package com.athkar.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.athkar.app.security.SessionManager
import com.athkar.designsystem.AthkarTheme
import com.athkar.feature.athkar.AthkarRoute
import com.athkar.feature.prayertimes.PrayerTimesRoute
import com.athkar.feature.prayertimes.QiblaRoute
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Compose host. Responsibilities per the delivery brief:
 *   - Hide the task snapshot while locked (FLAG_SECURE) and lock the session on backgrounding.
 *   - Route between the three top-level destinations: adhkar, prayer times and qibla.
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
            AthkarTheme {
                AthkarApp()
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

/** The three tabs are peers, not a stack: none of them is "inside" another. */
private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    ATHKAR("athkar", "الأذكار", Icons.Default.List),
    PRAYER("prayer", "الصلاة", Icons.Default.DateRange),
    QIBLA("qibla", "القبلة", Icons.Default.Place),
}

@Composable
private fun AthkarApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Tabs must not stack: returning to a tab restores it rather than
                                // pushing another copy on top of the back stack.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.ATHKAR.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.ATHKAR.route) { AthkarRoute() }
            composable(Destination.PRAYER.route) { PrayerTimesRoute() }
            composable(Destination.QIBLA.route) { QiblaRoute() }
        }
    }
}
