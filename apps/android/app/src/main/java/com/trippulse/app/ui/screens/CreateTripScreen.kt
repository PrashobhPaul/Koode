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
    var searchQuery by remember { mutableStateOf("") }
    var customWhen by remember { mutableStateOf("") }

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
        Text("Start a new journey", color = Teal, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        val myName by vm.myName.collectAsStateWithLifecycle()
        OutlinedTextField(
            value = myName, onValueChange = { vm.myName.value = it },
            label = { Text("Your name (your circle sees “…'s Journey”)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = origin, onValueChange = { vm.originText.value = it },
            label = { Text("From") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = dest, onValueChange = { vm.destText.value = it },
            label = { Text("Destination") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        // ---- search any place by name (OpenStreetMap, free) ----
        val results by vm.searchResults.collectAsStateWithLifecycle()
        val searching by vm.searching.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text("Search a place by name") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.searchPlaces(searchQuery) }, enabled = !searching) {
                if (searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                else Text("Search", fontSize = 13.sp)
            }
        }
        results.forEach { r ->
            SectionCard {
                Text(r.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { vm.useSearchResult(r, asStart = true) }) { Text("Use as From", color = Teal, fontSize = 13.sp) }
                    TextButton(onClick = { vm.useSearchResult(r, asStart = false) }) { Text("Use as To", color = Teal, fontSize = 13.sp) }
                }
            }
        }
        if (results.isNotEmpty()) {
            TextButton(onClick = { vm.clearSearch() }) { Text("Clear results", color = TextMid, fontSize = 12.sp) }
        }

        // ---- mode of transport (mandatory; drives the app's rules) ----
        val mode by vm.transportMode.collectAsStateWithLifecycle()
        val fuel by vm.fuelType.collectAsStateWithLifecycle()
        Text("How are you travelling? *", color = TextMid, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        // private vehicles
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("CAR" to "🚗 Car", "BIKE" to "🏍 Bike").forEach { (v, label) ->
                FilterChip(selected = mode == v, onClick = {
                    vm.transportMode.value = v
                    // bikes don't run on diesel
                    if (v == "BIKE" && vm.fuelType.value == "DIESEL") vm.fuelType.value = "PETROL"
                }, label = { Text(label, fontSize = 12.sp) })
                Spacer(Modifier.width(6.dp))
            }
        }
        // public transport
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("BUS" to "🚌 Bus", "TRAIN" to "🚆 Train", "FLIGHT" to "✈️ Flight").forEach { (v, label) ->
                FilterChip(selected = mode == v, onClick = { vm.transportMode.value = v },
                    label = { Text(label, fontSize = 12.sp) })
                Spacer(Modifier.width(6.dp))
            }
        }
        if (mode == "CAR" || mode == "BIKE") {
            val fuels = if (mode == "BIKE") listOf("PETROL" to "Petrol", "ELECTRIC" to "Electric")
                else listOf("PETROL" to "Petrol", "DIESEL" to "Diesel", "ELECTRIC" to "Electric")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fuel:", color = TextMid, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                fuels.forEach { (v, label) ->
                    FilterChip(selected = fuel == v, onClick = { vm.fuelType.value = v },
                        label = { Text(label, fontSize = 12.sp) })
                    Spacer(Modifier.width(6.dp))
                }
            }
        } else {
            val pnr by vm.pnr.collectAsStateWithLifecycle()
            val seat by vm.seat.collectAsStateWithLifecycle()
            val boarding by vm.boardingPoint.collectAsStateWithLifecycle()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pnr, onValueChange = { vm.pnr.value = it },
                    label = { Text("PNR / booking ref") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = seat, onValueChange = { vm.seat.value = it },
                    label = { Text("Seat") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = boarding, onValueChange = { vm.boardingPoint.value = it },
                label = {
                    Text(when (mode) {
                        "FLIGHT" -> "Airport"
                        "TRAIN" -> "Railway station"
                        else -> "Boarding point"
                    })
                },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        // ---- departure: leave now or schedule ahead ----
        val departure by vm.departureMs.collectAsStateWithLifecycle()
        Text("Departure", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = departure == null,
                onClick = { vm.departureMs.value = null; customWhen = "" },
                label = { Text("Now", fontSize = 12.sp) }
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = false,
                onClick = { vm.departureMs.value = System.currentTimeMillis() + 3_600_000L; customWhen = "" },
                label = { Text("+1 hour", fontSize = 12.sp) }
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = false,
                onClick = {
                    val cal = java.util.Calendar.getInstance().apply {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                        set(java.util.Calendar.HOUR_OF_DAY, 6); set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    vm.departureMs.value = cal.timeInMillis; customWhen = ""
                },
                label = { Text("Tomorrow 6 AM", fontSize = 12.sp) }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = customWhen, onValueChange = { customWhen = it },
                label = { Text("Or date & time (yyyy-MM-dd HH:mm)") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val parsed = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .parse(customWhen.trim())?.time
                }.getOrNull()
                if (parsed != null && parsed > System.currentTimeMillis()) vm.departureMs.value = parsed
            }) { Text("Set", fontSize = 13.sp) }
        }
        if (departure != null) {
            Text(
                "Scheduled: ${com.trippulse.app.core.TimeFmt.clockWithDay(departure!!, System.currentTimeMillis())} · reminder 30 min before",
                color = Teal, fontSize = 12.sp
            )
        }

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
