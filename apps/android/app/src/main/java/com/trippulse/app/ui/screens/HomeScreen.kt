package com.trippulse.app.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.ui.HomeVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextMid

@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeVm = viewModel(factory = HomeVm.Factory)
    val active by vm.activeTrip.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("TripPulse", color = Teal, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "Private live journey sharing with intelligent break tracking.",
            color = TextMid, fontSize = 15.sp
        )
        Spacer(Modifier.height(12.dp))

        if (active != null) {
            SectionCard {
                Text("Active trip", color = TextMid, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${active!!.originName} → ${active!!.destName}",
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { nav.navigate(Routes.driver(active!!.tripId)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) { Text("Resume trip", fontWeight = FontWeight.SemiBold) }
            }
        }

        Button(
            onClick = { nav.navigate(Routes.CREATE) },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) { Text("Start new trip", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        OutlinedButton(
            onClick = { nav.navigate(Routes.JOIN) },
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { Text("Join a trip", fontSize = 16.sp) }

        Spacer(Modifier.height(8.dp))
        Text(
            if (vm.cloudAvailable) "Cloud sync: enabled — viewers can follow live."
            else "Running in local mode. Add google-services.json to enable live viewer sharing.",
            color = TextMid, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
