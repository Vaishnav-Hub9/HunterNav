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
                    NavHost(navController = navController, startDestination = Routes.SPLASH) {
                        composable(Routes.SPLASH) {
                            SplashScreen(onReady = {
                                navController.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
                            })
                        }
                        composable(Routes.HOME) {
                            HomeScreen(
                                container = appContainer,
                                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onDestinationPicked = { navController.navigate(Routes.ROUTE_PREVIEW) },
                                onStartNavigation = { navController.navigate(Routes.NAVIGATION) },
                            )
                        }
                        composable(Routes.SEARCH) {
                            SearchScreen(
                                container = appContainer,
                                onDestinationConfirmed = {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle?.set("destination_confirmed", true)
                                    navController.popBackStack()
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Routes.ROUTE_PREVIEW) {
                            RoutePreviewScreen(
                                container = appContainer,
                                onChangeDestination = {
                                    navController.navigate(Routes.SEARCH) { launchSingleTop = true }
                                },
                                onStartNavigation = { navController.navigate(Routes.NAVIGATION) { launchSingleTop = true } },
                            )
                        }
                        composable(Routes.NAVIGATION) {
                            NavigationScreen(container = appContainer, onExit = { navController.popBackStack() })
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
