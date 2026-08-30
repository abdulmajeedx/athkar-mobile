package com.athkar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import com.athkar.designsystem.AthkarTheme
import com.athkar.designsystem.Elevation
import com.athkar.feature.athkar.AthkarRoute
import com.athkar.feature.prayertimes.PrayerTimesRoute
import com.athkar.feature.prayertimes.QiblaRoute
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity Compose host: routes between the three top-level destinations — adhkar, prayer
 * times and qibla.
 *
 * There is deliberately no FLAG_SECURE. It was here to hide the task snapshot of a locked session,
 * but the lock screen was never built, so the flag was added on the first onPause and — with
 * nothing able to unlock the session — never cleared again. The effect on a dhikr app was that
 * screenshots stopped working the moment the user backgrounded it once, which is the opposite of
 * what someone wanting to share a dhikr needs.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AthkarTheme {
                AthkarApp()
            }
        }
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
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Material derives a navigation bar's colour from the scheme's surface tints, which for
            // this palette lands on lavender — a colour that appears nowhere else in the app. Named
            // explicitly so the bar belongs to the design rather than to the framework's defaults.
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = Elevation.flat,
            ) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
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
