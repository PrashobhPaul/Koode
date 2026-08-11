package com.trippulse.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

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

        Text("Long-press the map to drop the destination pin.", color = TextMid, fontSize = 12.sp)
        MapPanel(
            current = null,
            origin = vm.pickedOrigin.collectAsStateWithLifecycle().value,
            destination = pickedDest,
            route = emptyList(),
            heightDp = 220,
            onLongPress = { vm.pickedDest.value = it }
        )
        if (pickedDest != null) {
            Text(
                "Destination pin set (%.4f, %.4f)".format(pickedDest!!.lat, pickedDest!!.lng),
                color = TextMid, fontSize = 12.sp
            )
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
