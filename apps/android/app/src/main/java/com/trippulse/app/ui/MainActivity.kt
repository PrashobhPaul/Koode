package com.trippulse.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trippulse.app.ui.screens.CreateTripScreen
import com.trippulse.app.ui.screens.CredentialsScreen
import com.trippulse.app.ui.screens.DriverScreen
import com.trippulse.app.ui.screens.HomeScreen
import com.trippulse.app.ui.screens.JoinViewerScreen
import com.trippulse.app.ui.screens.ReplayScreen
import com.trippulse.app.ui.screens.SummaryScreen
import com.trippulse.app.ui.screens.ViewerScreen
import com.trippulse.app.ui.theme.TripPulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TripPulseTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    AppNav(Modifier.padding(padding))
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
    const val REPLAY = "replay/{tripId}"
    const val SUMMARY = "summary/{tripId}"

    fun credentials(tripId: String) = "credentials/$tripId"
    fun driver(tripId: String) = "driver/$tripId"
    fun viewer(accessKey: String) = "viewer/$accessKey"
    fun replay(tripId: String) = "replay/$tripId"
    fun summary(tripId: String) = "summary/$tripId"
}

@Composable
fun AppNav(modifier: Modifier = Modifier, nav: NavHostController = rememberNavController()) {
    NavHost(navController = nav, startDestination = Routes.HOME, modifier = modifier) {
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
        composable(Routes.REPLAY) { back ->
            ReplayScreen(nav, back.arguments?.getString("tripId").orEmpty())
        }
        composable(Routes.SUMMARY) { back ->
            SummaryScreen(nav, back.arguments?.getString("tripId").orEmpty())
        }
    }
}
