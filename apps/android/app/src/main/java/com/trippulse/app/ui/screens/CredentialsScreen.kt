package com.trippulse.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.trippulse.app.TripPulseApp
import com.trippulse.app.data.local.ActiveTripEntity
import com.trippulse.app.service.TripTrackingService
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid
import kotlinx.coroutines.launch

@Composable
fun CredentialsScreen(nav: NavHostController, tripId: String) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TripPulseApp).graph
    val scope = rememberCoroutineScope()
    val clipboard: ClipboardManager = LocalClipboardManager.current

    var trip by remember { mutableStateOf<ActiveTripEntity?>(null) }
    var starting by remember { mutableStateOf(false) }
    var permMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tripId) { trip = graph.db.tripDao().byId(tripId) }

    fun beginTrip() {
        starting = true
        scope.launch {
            graph.tripManager.startTrip(tripId)
            TripTrackingService.start(context)
            starting = false
            nav.navigate(Routes.driver(tripId)) { popUpTo(Routes.HOME) }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            beginTrip()
        } else {
            permMessage = "Location permission is required to track the trip. Please allow it to continue."
        }
    }

    fun requestPermissionsAndStart() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.toTypedArray()
        permLauncher.launch(perms)
    }

    val t = trip
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Trip created", color = Teal, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Share these with the people who will follow your trip. They're shown once — keep them private.", color = TextMid, fontSize = 13.sp)

        SectionCard("TRIP ID") {
            Text(t?.tripId ?: "…", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Teal)
        }
        SectionCard("SECRET PASSWORD") {
            Text(t?.secret ?: "…", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    if (t != null) clipboard.setText(AnnotatedString("TripPulse\nTrip ID: ${t.tripId}\nPassword: ${t.secret}"))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Copy") }
            OutlinedButton(
                onClick = {
                    if (t != null) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Follow my TripPulse trip.\nTrip ID: ${t.tripId}\nPassword: ${t.secret}")
                        }
                        context.startActivity(Intent.createChooser(send, "Share trip credentials"))
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Share") }
        }

        if (!graph.cloudEnabledByDefault()) {
            Text("Note: this build is in local mode, so remote viewers can't connect until Firebase is configured. Tracking still works fully on this phone.", color = TextMid, fontSize = 12.sp)
        }

        if (permMessage != null) Text(permMessage!!, color = Amber, fontSize = 13.sp)

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { requestPermissionsAndStart() },
            enabled = t != null && !starting,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) { Text("Start trip", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}
