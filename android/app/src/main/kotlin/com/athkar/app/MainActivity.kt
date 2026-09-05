package com.athkar.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
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

/**
 * The three tabs are peers, not a stack: none of them is "inside" another.
 *
 * Each is addressable by a private `athkar://` URI so something outside the UI — a prayer alert, at
 * four in the morning, on a cold process — can open the tab it is talking about instead of dropping
 * the user on whichever tab happens to start.
 */
private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /**
     * The host is spelled out rather than derived from [route] because the adhkar tab's is already
     * public: `athkar://adhkar` is the browsable filter the manifest has always advertised, and it
     * matched no destination until now.
     */
    val deepLink: String,
) {
    ATHKAR("athkar", "الأذكار", Icons.AutoMirrored.Filled.MenuBook, "athkar://adhkar"),
    PRAYER("prayer", "الصلاة", Icons.Default.Schedule, "athkar://prayer"),
    QIBLA("qibla", "القبلة", Icons.Default.Explore, "athkar://qibla"),
}

@Composable
private fun AthkarApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val activity = LocalContext.current as? ComponentActivity

    // launchMode is singleTask, so a second alert while the app is already open arrives here rather
    // than through onCreate; without this the notification would silently do nothing.
    DisposableEffect(activity, navController) {
        val listener = Consumer<Intent> { navController.handleDeepLink(it) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    // Back from a peer tab returns to the first one rather than closing the app. The start
    // destination is meant to be the last screen standing between the user and the launcher.
    val atStart = currentDestination?.hierarchy?.any { it.route == Destination.ATHKAR.route } == true
    BackHandler(enabled = !atStart) {
        navController.navigate(Destination.ATHKAR.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

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
            Destination.entries.forEach { destination ->
                composable(
                    route = destination.route,
                    deepLinks = listOf(navDeepLink { uriPattern = destination.deepLink }),
                ) {
                    when (destination) {
                        Destination.ATHKAR -> AthkarRoute()
                        Destination.PRAYER -> PrayerTimesRoute()
                        Destination.QIBLA -> QiblaRoute()
                    }
                }
            }
        }
    }
}
