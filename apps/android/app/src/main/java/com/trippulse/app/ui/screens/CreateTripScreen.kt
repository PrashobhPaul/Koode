package com.trippulse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.ui.CreateVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid

@Composable
fun CreateTripScreen(nav: NavHostController) {
    val vm: CreateVm = viewModel(factory = CreateVm.Factory)
    val origin by vm.originText.collectAsStateWithLifecycle()
    val dest by vm.destText.collectAsStateWithLifecycle()
    val emName by vm.emergencyName.collectAsStateWithLifecycle()
    val emPhone by vm.emergencyPhone.collectAsStateWithLifecycle()
    val pickedDest by vm.pickedDest.collectAsStateWithLifecycle()
    val pickedOrigin by vm.pickedOrigin.collectAsStateWithLifecycle()
    val pinMode by vm.pinMode.collectAsStateWithLifecycle()
    val places by vm.savedPlaces.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var newPlaceName by remember { mutableStateOf("") }

    // "Current location" as a start point needs the location permission, which
    // was previously only requested AFTER creating the trip — ask up front.
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Start a new trip", color = Teal, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = origin, onValueChange = { vm.originText.value = it },
            label = { Text("From") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = dest, onValueChange = { vm.destText.value = it },
            label = { Text("Destination") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        // ---- saved places: one-tap From / To ----
        if (places.isNotEmpty()) {
            Text("Saved places", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            places.forEach { p ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.name, color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.useAsStart(p) }) { Text("From", color = Teal, fontSize = 13.sp) }
                    TextButton(onClick = { vm.useAsDest(p) }) { Text("To", color = Teal, fontSize = 13.sp) }
                    TextButton(onClick = { vm.deletePlace(p.name) }) { Text("✕", color = TextMid, fontSize = 13.sp) }
                }
            }
        }

        // ---- map pinning ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Long-press the map to pin:", color = TextMid, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = pinMode == "DEST",
                onClick = { vm.pinMode.value = "DEST" },
                label = { Text("Destination", fontSize = 12.sp) }
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = pinMode == "START",
                onClick = { vm.pinMode.value = "START" },
                label = { Text("Start", fontSize = 12.sp) }
            )
        }
        MapPanel(
            current = null,
            origin = pickedOrigin,
            destination = pickedDest,
            route = emptyList(),
            heightDp = 220,
            onLongPress = { vm.onMapLongPress(it) }
        )
        if (pickedOrigin != null) {
            Text(
                "Start pin set (%.4f, %.4f)".format(pickedOrigin!!.lat, pickedOrigin!!.lng),
                color = TextMid, fontSize = 12.sp
            )
        }
        if (pickedDest != null) {
            Text(
                "Destination pin set (%.4f, %.4f)".format(pickedDest!!.lat, pickedDest!!.lng),
                color = TextMid, fontSize = 12.sp
            )
        }

        // ---- save a place for next time ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newPlaceName, onValueChange = { newPlaceName = it },
                label = { Text("Save place (e.g. Home)") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                vm.savePlace(newPlaceName)
                newPlaceName = ""
            }) { Text("Save", fontSize = 13.sp) }
        }
        Text(
            "Saves the last dropped pin — or your current location if no pin — so next time you can pick Home/Office with one tap.",
            color = TextMid, fontSize = 11.sp
        )

        Text("Emergency contact (optional)", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = emName, onValueChange = { vm.emergencyName.value = it },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = emPhone, onValueChange = { vm.emergencyPhone.value = it },
            label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        if (error != null) {
            Text(error!!, color = Danger, fontSize = 13.sp)
        }

        Button(
            onClick = { vm.create { tripId -> nav.navigate(Routes.credentials(tripId)) { popUpTo(Routes.HOME) } } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
            else Text("Create trip", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel", color = TextMid)
        }
    }
}
