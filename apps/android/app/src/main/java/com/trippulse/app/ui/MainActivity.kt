package com.trippulse.app.ui

import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trippulse.app.TripPulseApp
import com.trippulse.app.service.TrackingResume
import com.trippulse.app.ui.components.KoodeHaptics
import com.trippulse.app.ui.components.LocalDims
import com.trippulse.app.ui.components.LocalHaptics
import com.trippulse.app.ui.components.LocalWindowClass
import com.trippulse.app.ui.components.rememberDims
import com.trippulse.app.ui.components.rememberWindowClass
import com.trippulse.app.ui.screens.AboutScreen
import com.trippulse.app.ui.screens.CreateTripScreen
import com.trippulse.app.ui.screens.CredentialsScreen
import com.trippulse.app.ui.screens.DriverScreen
import com.trippulse.app.ui.screens.HomeScreen
import com.trippulse.app.ui.screens.JoinViewerScreen
import com.trippulse.app.ui.screens.SettingsPageScreen
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
        // A place shared from Google Maps while Koode wasn't running.
        // Not gated on savedInstanceState: if Android closed Koode while Maps was
        // open, the recreated activity must still take the shared place (once).
        if (intent?.getBooleanExtra(SHARE_HANDLED, false) != true) {
            acceptShare(intent)
            acceptViewLink(intent)
            intent?.putExtra(SHARE_HANDLED, true)
        }

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
                val windowClass = rememberWindowClass()
                val view = LocalView.current
                val haptics = remember(view, settings.hapticFeedback) { KoodeHaptics(view, settings.hapticFeedback) }
                CompositionLocalProvider(
                    LocalWindowClass provides windowClass,
                    LocalDims provides rememberDims(windowClass),
                    LocalHaptics provides haptics
                ) {
                    Box(Modifier.fillMaxSize().background(KoodeTheme.colors.background)) {
                        val nav = rememberNavController()
                        AppNav(nav = nav)

                        // A shared place always lands on the planning screen,
                        // which picks it up from the inbox and fills the field.
                        val shared by SharedPlaceInbox.pending.collectAsStateWithLifecycle()
                        LaunchedEffect(shared) {
                            if (shared != null && !SharedPlaceInbox.pickerOpen && nav.currentDestination?.route != Routes.CREATE) {
                                nav.navigate(Routes.CREATE) { launchSingleTop = true }
                            }
                        }

                        // A tapped "follow this journey" link lands on the Follow
                        // screen, which reads the code/passcode from the inbox.
                        val follow by FollowLinkInbox.pending.collectAsStateWithLifecycle()
                        LaunchedEffect(follow) {
                            if (follow != null && nav.currentDestination?.route != Routes.JOIN) {
                                nav.navigate(Routes.JOIN) { launchSingleTop = true }
                            }
                        }

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

    /**
     * An open journey whose tracking service has died is brought back here,
     * where Android allows a location service to start. Covers a force stop,
     * an update, a reboot the boot receiver missed, and a battery manager that
     * removed the service overnight: opening the app is enough.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { TrackingResume.ensureRunning(this@MainActivity) }
    }

    /**
     * Back from Google Maps with a copied link and no picker on screen (Android
     * closed Koode meanwhile): take the link from the clipboard like a share.
     * While a picker is open it does this itself, on its own window.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || SharedPlaceInbox.pickerOpen || !SharedPlaceInbox.isAwaiting()) return
        val clip = runCatching {
            getSystemService(android.content.ClipboardManager::class.java)
                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        SharedPlaceInbox.acceptReturnedClip(clip)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.putExtra(SHARE_HANDLED, true)
        setIntent(intent)
        acceptShare(intent)
        acceptViewLink(intent)
    }
}

/** Shared text arriving while the app is already open (singleTask). */
private const val SHARE_HANDLED = "app.koode.SHARE_HANDLED"

private fun MainActivity.acceptShare(intent: Intent?) {
    if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
        SharedPlaceInbox.offer(intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}

/** A "follow this journey" link (from a message or a QR) opened Koode. */
private fun MainActivity.acceptViewLink(intent: Intent?) {
    if (intent?.action == Intent.ACTION_VIEW) FollowLinkInbox.offer(intent.data)
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
    /** Planning, opened with "later" already chosen. */
    const val CREATE_LATER = "create/later"
    const val SETTINGS = "settings/{page}"

    fun credentials(tripId: String) = "credentials/$tripId"
    fun driver(tripId: String) = "driver/$tripId"
    fun viewer(accessKey: String) = "viewer/$accessKey"
    fun summary(tripId: String) = "summary/$tripId"
    fun settings(page: String) = "settings/$page"
}

/**
 * A request, from a pushed screen, for Home to show one of its tabs once it is
 * back on top (e.g. Settings › Journey followers → People).
 */
object HomeTabs {
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val pending: kotlinx.coroutines.flow.StateFlow<Int?> = _pending

    fun request(tab: Int) { _pending.value = tab }
    fun consumed() { _pending.value = null }
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
        composable(Routes.CREATE_LATER) { CreateTripScreen(nav, scheduleLater = true) }
        composable(Routes.SETTINGS) { back ->
            SettingsPageScreen(nav, back.arguments?.getString("page").orEmpty())
        }
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
