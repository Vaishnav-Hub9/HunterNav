package com.hunternav.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hunternav.HunterNavApplication
import com.hunternav.core.util.DebugLog
import com.hunternav.di.AppContainer
import com.hunternav.ui.home.HomeScreen
import com.hunternav.ui.navigation.NavigationScreen
import com.hunternav.ui.routepreview.RoutePreviewScreen
import com.hunternav.ui.search.SearchScreen
import com.hunternav.ui.settings.SettingsScreen
import com.hunternav.ui.splash.SplashScreen
import com.hunternav.ui.theme.HunterNavTheme

/** Route names for the single-activity Compose graph. */
object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val SEARCH = "search"
    const val ROUTE_PREVIEW = "route_preview"
    const val NAVIGATION = "navigation"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    lateinit var container: AppContainer
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as HunterNavApplication).container

        setContent {
            HunterNavTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val appContainer = container
                    // Single-top everywhere: rapidly tapping a button must never stack
                    // duplicate entries (duplicate screens = back-button loops).
                    fun navigateTo(route: String) {
                        DebugLog.d(
                            "SCREEN_TRANSITION",
                            "navigate to=$route from=${navController.currentDestination?.route ?: "?"}",
                        )
                        navController.navigate(route) { launchSingleTop = true }
                    }

                    NavHost(navController = navController, startDestination = Routes.SPLASH) {
                        composable(Routes.SPLASH) {
                            SplashScreen(onReady = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.SPLASH) { inclusive = true }
                                    launchSingleTop = true
                                }
                            })
                        }
                        composable(Routes.HOME) {
                            HomeScreen(
                                container = appContainer,
                                onOpenSearch = { navigateTo(Routes.SEARCH) },
                                onOpenSettings = { navigateTo(Routes.SETTINGS) },
                                onDestinationPicked = { navigateTo(Routes.ROUTE_PREVIEW) },
                                onStartNavigation = { navigateTo(Routes.NAVIGATION) },
                            )
                        }
                        composable(Routes.SEARCH) {
                            SearchScreen(
                                container = appContainer,
                                // Selecting a result lands on Route Preview exactly once,
                                // whether Search was opened from Home or from Preview:
                                // everything above Home is replaced by a single preview entry,
                                // so Back from Preview always goes to Home (no loops).
                                onDestinationConfirmed = {
                                    DebugLog.d(
                                        "SCREEN_TRANSITION",
                                        "navigate to=${Routes.ROUTE_PREVIEW} from=${Routes.SEARCH} (destination confirmed)",
                                    )
                                    navController.navigate(Routes.ROUTE_PREVIEW) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                },
                                onBack = {
                                    DebugLog.d("SCREEN_TRANSITION", "back search -> previous")
                                    navController.popBackStack()
                                },
                            )
                        }
                        composable(Routes.ROUTE_PREVIEW) {
                            RoutePreviewScreen(
                                container = appContainer,
                                onChangeDestination = { navigateTo(Routes.SEARCH) },
                                onStartNavigation = { navigateTo(Routes.NAVIGATION) },
                            )
                        }
                        composable(Routes.NAVIGATION) {
                            // Exiting navigation (button or system back, see NavigationScreen's
                            // BackHandler) always tears the trip down first and returns to Home
                            // exactly once — never back onto a stale Route Preview.
                            NavigationScreen(
                                container = appContainer,
                                onExit = {
                                    DebugLog.d("SCREEN_TRANSITION", "exit navigation -> home")
                                    navController.popBackStack(Routes.HOME, inclusive = false)
                                },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(container = appContainer, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
