package com.trippulse.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.ui.JoinVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Danger
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid

@Composable
fun JoinViewerScreen(nav: NavHostController) {
    val vm: JoinVm = viewModel(factory = JoinVm.Factory)
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var tripId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var viewerName by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Follow a trip", color = Teal, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Enter the trip id and password the driver shared with you.", color = TextMid, fontSize = 13.sp)

        OutlinedTextField(
            value = tripId, onValueChange = { tripId = it.uppercase() },
            label = { Text("Trip ID (e.g. TP-XXXX-XXXX)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = secret, onValueChange = { secret = it.uppercase() },
            label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = viewerName, onValueChange = { viewerName = it },
            label = { Text("Your name (shown to the driver)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        if (error != null) Text(error!!, color = Danger, fontSize = 13.sp)
        if (!vm.cloudAvailable()) {
            Text("This build is in local mode — remote following needs the cloud backend configured.", color = TextMid, fontSize = 12.sp)
        }

        Button(
            onClick = { vm.join(tripId, secret, viewerName) { key -> nav.navigate(Routes.viewer(key)) } },
            enabled = !busy && tripId.isNotBlank() && secret.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
            else Text("Follow trip", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Recent", color = TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            saved.forEach { v ->
                SectionCard(modifier = Modifier.clickable { nav.navigate(Routes.viewer(v.accessKey)) }) {
                    Text(v.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(v.tripId, color = TextMid, fontSize = 12.sp)
                }
            }
        }
    }
}
