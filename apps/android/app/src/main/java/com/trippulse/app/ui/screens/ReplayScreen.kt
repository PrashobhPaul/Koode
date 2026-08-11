package com.trippulse.app.ui.screens

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.domain.GeoPoint
import com.trippulse.app.ui.ReplayVm
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextHigh
import com.trippulse.app.ui.theme.TextMid

@Composable
fun ReplayScreen(nav: NavHostController, tripId: String) {
    val vm: ReplayVm = viewModel(factory = ReplayVm.factory(tripId))
    val samples by vm.samples.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    val points = remember(samples) { samples.map { GeoPoint(it.lat, it.lng) } }
    var index by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(5) }

    // advance playback
    LaunchedEffect(playing, speed, points.size) {
        if (!playing || points.size < 2) return@LaunchedEffect
        while (playing && index < (points.size - 1).toFloat()) {
            kotlinx.coroutines.delay(300L)
            index = (index + speed).coerceAtMost((points.size - 1).toFloat())
            if (index >= (points.size - 1).toFloat()) playing = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trip replay", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        if (loading) {
            Text("Loading route…", color = TextMid, fontSize = 13.sp)
        } else if (points.isEmpty()) {
            Text("No location history was recorded for this trip.", color = TextMid, fontSize = 13.sp)
        } else {
            val idx = index.toInt().coerceIn(0, points.size - 1)
            MapPanel(
                current = points[idx],
                origin = points.firstOrNull(),
                destination = points.lastOrNull(),
                route = emptyList(),
                breadcrumb = points.subList(0, (idx + 1).coerceAtMost(points.size)),
                heightDp = 300
            )
            val tMs = samples[idx].tMs
            Text(TimeFmt.clockWithDay(tMs, System.currentTimeMillis()), color = TextHigh, fontSize = 14.sp, fontWeight = FontWeight.Medium)

            Slider(
                value = index,
                onValueChange = { index = it; playing = false },
                valueRange = 0f..(points.size - 1).toFloat().coerceAtLeast(1f)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { if (index >= (points.size - 1).toFloat()) index = 0f; playing = !playing },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) { Text(if (playing) "Pause" else "Play") }
                OutlinedButton(onClick = { speed = when (speed) { 1 -> 5; 5 -> 20; else -> 1 } }, modifier = Modifier.weight(1f)) {
                    Text("${speed}×")
                }
            }
            Text("${idx + 1} / ${points.size} points", color = TextMid, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
