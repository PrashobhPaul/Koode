package com.trippulse.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trippulse.app.BuildConfig
import com.trippulse.app.core.Profile
import com.trippulse.app.ui.SettingsVm
import com.trippulse.app.ui.theme.Amber
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid

/**
 * Settings — the mandatory prerequisite before the first journey: the
 * traveller's name, at least one saved location and at least two emergency
 * contacts. Also carries the Privacy & Legal and About sections expected of
 * a personal-safety app.
 */
@Composable
fun SettingsTab(onProfileChanged: () -> Unit) {
    val vm: SettingsVm = viewModel(factory = SettingsVm.Factory)
    val context = LocalContext.current
    val places by vm.savedPlaces.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(Profile.name(context)) }
    var c1 by remember { mutableStateOf(Profile.contact(context, 1)) }
    var c2 by remember { mutableStateOf(Profile.contact(context, 2)) }
    var c3 by remember { mutableStateOf(Profile.contact(context, 3)) }
    var placeLabel by remember { mutableStateOf("") }
    var placeQuery by remember { mutableStateOf("") }

    val missing = Profile.missing(context, places.size)
    if (missing.isNotEmpty()) {
        SectionCard {
            Text("Complete your profile to start using Koode", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            missing.forEach { Text("• $it", color = TextMid, fontSize = 13.sp) }
        }
    }

    // ---- profile ----
    Text("PROFILE", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("Your full name *") }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )

    // ---- saved locations ----
    Text("SAVED LOCATIONS  (optional — one-tap From/To)", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    places.forEach { p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("%.4f, %.4f".format(p.lat, p.lng), color = TextMid, fontSize = 11.sp)
            TextButton(onClick = { vm.deletePlace(p.name) }) { Text("✕", color = TextMid, fontSize = 13.sp) }
        }
    }
    OutlinedTextField(
        value = placeLabel, onValueChange = { placeLabel = it },
        label = { Text("Location name (Home / Office / …)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.addCurrentLocation(placeLabel) }, modifier = Modifier.weight(1f)) {
            Text("Use current location", fontSize = 13.sp)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = placeQuery, onValueChange = { placeQuery = it },
            label = { Text("…or search a place") }, singleLine = true, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { vm.searchPlaces(placeQuery) }, enabled = !searching) {
            if (searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
            else Text("Search", fontSize = 13.sp)
        }
    }
    results.forEach { r ->
        SectionCard {
            Text(r.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            TextButton(onClick = { vm.addPlace(placeLabel, r.point, r.name); placeLabel = ""; placeQuery = "" }) {
                Text("Save this place", color = Teal, fontSize = 13.sp)
            }
        }
    }

    // ---- emergency contacts ----
    Text("EMERGENCY CONTACTS  (* at least ${Profile.MIN_CONTACTS})", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    ContactRow("Contact 1 *", c1) { c1 = it }
    ContactRow("Contact 2 *", c2) { c2 = it }
    ContactRow("Contact 3", c3) { c3 = it }

    if (message != null) Text(message!!, color = Teal, fontSize = 13.sp)

    Button(
        onClick = {
            vm.saveProfile(name, listOf(c1, c2, c3))
            onProfileChanged()
        },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Teal)
    ) { Text("Save profile", fontWeight = FontWeight.SemiBold) }

    // ---- privacy & legal ----
    Spacer(Modifier.height(8.dp))
    Text("PRIVACY & LEGAL", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    SectionCard {
        Text(
            "Your location is shared only during a journey you started, only with people you approve by name, " +
                "and every journey self-destructs from the cloud 30 minutes after arrival. Your profile, emergency " +
                "contacts, history and expenses stay on this phone. No ads, no analytics, no accounts.",
            color = TextMid, fontSize = 12.sp
        )
        Row {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/PrashobhPaul/TripPulse/blob/main/docs/PRIVACY.md")))
            }) { Text("Privacy policy", color = Teal, fontSize = 13.sp) }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/PrashobhPaul/TripPulse/blob/main/docs/TERMS.md")))
            }) { Text("Terms of use", color = Teal, fontSize = 13.sp) }
        }
    }

    // ---- about ----
    Text("ABOUT", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    SectionCard {
        Text("Koode ${BuildConfig.VERSION_NAME} — Always with you.", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Text(
            "A personal journey companion that keeps the people you love informed about your journey, wellbeing and safety — without requiring you to constantly call or message them.",
            color = TextMid, fontSize = 12.sp
        )
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ContactRow(label: String, contact: Profile.Contact, onChange: (Profile.Contact) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = contact.name, onValueChange = { onChange(contact.copy(name = it)) },
            label = { Text("$label — name") }, singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = contact.phone, onValueChange = { onChange(contact.copy(phone = it)) },
            label = { Text("Phone") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
    }
}
