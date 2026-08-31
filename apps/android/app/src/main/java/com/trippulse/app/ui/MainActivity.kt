package com.trippulse.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trippulse.app.TripPulseApp
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.rememberWindowClass
import com.trippulse.app.ui.screens.AboutScreen
import com.trippulse.app.ui.screens.CreateTripScreen
import com.trippulse.app.ui.screens.CredentialsScreen
import com.trippulse.app.ui.screens.DriverScreen
import com.trippulse.app.ui.screens.HomeScreen
import com.trippulse.app.ui.screens.JoinViewerScreen
import com.trippulse.app.ui.screens.SplashScreen
import com.trippulse.app.ui.screens.SummaryScreen
import com.trippulse.app.ui.screens.ViewerScreen
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Motion
import com.trippulse.app.ui.theme.TripPulseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The platform splash screen holds the Koode mark until the first frame
        // is ready, so cold start never shows an empty window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as TripPulseApp).graph

        setContent {
            val settings by graph.settings.state.collectAsStateWithLifecycle()

            // Honour "keep the screen on during a journey" without leaking the
            // flag once the setting is turned off.
            if (settings.keepScreenOnDuringJourney) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            TripPulseTheme(themeMode = settings.themeMode) {
                CompositionLocalProvider(LocalWindowClass provides rememberWindowClass()) {
                    Box(Modifier.fillMaxSize().background(KoodeTheme.colors.background)) {
                        AppNav()

                        // The animated splash rides above the app on a cold start
                        // and fades away to reveal Home. Kept as an overlay rather
                        // than a nav destination so the hand-off is a clean
                        // cross-fade and Home is already composed underneath.
                        var showSplash by rememberSaveable { mutableStateOf(true) }
                        AnimatedVisibility(
                            visible = showSplash,
                            enter = fadeIn(tween(0)),
                            exit = fadeOut(tween(Motion.slow))
                        ) {
                            SplashScreen(onDone = { showSplash = false })
                        }
                    }
                }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val CREDENTIALS = "credentials/{tripId}"
    const val DRIVER = "driver/{tripId}"
    const val JOIN = "join"
    const val VIEWER = "viewer/{accessKey}"
    const val SUMMARY = "summary/{tripId}"
    const val ABOUT = "about"

    fun credentials(tripId: String) = "credentials/$tripId"
    fun driver(tripId: String) = "driver/$tripId"
    fun viewer(accessKey: String) = "viewer/$accessKey"
    fun summary(tripId: String) = "summary/$tripId"
}

/**
 * Navigation, with directional transitions.
 *
 * Screens slide in from the trailing edge and back out the way they came, which
 * is what makes Android's system back gesture feel connected to the app rather
 * than merely tolerated by it.
 */
@Composable
fun AppNav(modifier: Modifier = Modifier, nav: NavHostController = rememberNavController()) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(Motion.normal)) +
                fadeIn(tween(Motion.normal))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(Motion.normal)) +
                fadeOut(tween(Motion.fast))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(Motion.normal)) +
                fadeIn(tween(Motion.normal))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(Motion.normal)) +
                fadeOut(tween(Motion.fast))
        }
    ) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.CREATE) { CreateTripScreen(nav) }
        composable(Routes.CREDENTIALS) { back ->
            CredentialsScreen(nav, back.arguments?.getString("tripId").orEmpty())
        }
        composable(Routes.DRIVER) { back ->
            DriverScreen(nav, back.arguments?.getString("tripId").orEmpty())
        }
        composable(Routes.JOIN) { JoinViewerScreen(nav) }
        composable(Routes.VIEWER) { back ->
            ViewerScreen(nav, back.arguments?.getString("accessKey").orEmpty())
        }
        composable(Routes.SUMMARY) { back ->
            SummaryScreen(nav, back.arguments?.getString("tripId").orEmpty())
        }
        composable(Routes.ABOUT) { AboutScreen(nav) }
    }
}
