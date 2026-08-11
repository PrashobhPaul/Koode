package com.trippulse.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.TimeFmt
import com.trippulse.app.data.EventCodec
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.SummaryVm
import com.trippulse.app.ui.theme.Teal
import com.trippulse.app.ui.theme.TextHigh
import com.trippulse.app.ui.theme.TextMid

@Composable
fun SummaryScreen(nav: NavHostController, tripId: String) {
    val vm: SummaryVm = viewModel(factory = SummaryVm.factory(tripId))
    val trip by vm.trip.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()

    val summary = remember(events) {
        events.firstOrNull { it.type == EventTypes.TRIP_COMPLETED }
            ?.let { EventCodec.payloadFromJson(it.payloadJson) }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trip summary", color = Teal, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${trip?.originName ?: "Start"} → ${trip?.destName ?: "Destination"}", color = TextMid, fontSize = 14.sp)

        if (summary == null) {
            Text("The summary will appear once the trip is completed.", color = TextMid, fontSize = 13.sp)
        } else {
            fun d(k: String) = (summary[k] as? Number)?.toDouble() ?: 0.0
            fun l(k: String) = (summary[k] as? Number)?.toLong() ?: 0L
            fun i(k: String) = (summary[k] as? Number)?.toInt() ?: 0

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Distance", "%.0f km".format(d("distanceKm")), Modifier.weight(1f))
                Stat("Driving", TimeFmt.durationShort(l("drivingSeconds")), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Total time", TimeFmt.durationShort(l("totalSeconds")), Modifier.weight(1f))
                Stat("Days", i("days").toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Stops", i("stops").toString(), Modifier.weight(1f))
                Stat("Longest leg", TimeFmt.durationShort(l("longestLegSeconds")), Modifier.weight(1f))
            }

            SectionCard("BREAKS") {
                BreakLine("💧 Water", i("waterConfirmations"))
                BreakLine("🍛 Food", i("foodBreaks"))
                BreakLine("🚻 Toilet", i("toiletBreaks"))
                BreakLine("😴 Rest", i("restBreaks"))
                BreakLine("⛽ Fuel", i("fuelStops"))
            }

            // ---- owner-only journey costs (local to this phone) ----
            val expenses by vm.expenses.collectAsStateWithLifecycle()
            if (expenses.isNotEmpty()) {
                val fuelCost = expenses.filter { it.type == "FUEL" }.sumOf { it.amount }
                val litres = expenses.filter { it.type == "FUEL" && it.unit == "L" }.sumOf { it.quantity ?: 0.0 }
                val kwh = expenses.filter { it.type == "FUEL" && it.unit == "kWh" }.sumOf { it.quantity ?: 0.0 }
                val foodCost = expenses.filter { it.type == "FOOD" }.sumOf { it.amount }
                val stayCost = expenses.filter { it.type == "STAY" }.sumOf { it.amount }
                val otherCost = expenses.filter { it.type == "OTHER" }.sumOf { it.amount }
                val total = fuelCost + foodCost + stayCost + otherCost
                val distKm = d("distanceKm")

                SectionCard("JOURNEY COST (only you can see this)") {
                    if (fuelCost > 0) CostLine("⛽ Fuel", fuelCost)
                    if (foodCost > 0) CostLine("🍛 Food", foodCost)
                    if (stayCost > 0) CostLine("🏨 Accommodation", stayCost)
                    if (otherCost > 0) CostLine("🧾 Other", otherCost)
                    Spacer(Modifier.height(6.dp))
                    CostLine("Total", total, bold = true)
                    if (distKm > 0 && total > 0) {
                        Text("%.2f per km overall".format(total / distKm), color = TextMid, fontSize = 12.sp)
                    }
                    if (litres > 0 && distKm > 0) {
                        Text(
                            "Fuel efficiency: %.1f km/L (%.1f L used)".format(distKm / litres, litres),
                            color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (kwh > 0 && distKm > 0) {
                        Text(
                            "EV efficiency: %.1f km/kWh (%.1f kWh used)".format(distKm / kwh, kwh),
                            color = Teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { nav.navigate(Routes.replay(tripId)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) { Text("Watch replay") }
        OutlinedButton(
            onClick = { nav.popBackStack(Routes.HOME, inclusive = false) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Done") }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text(label, color = TextMid, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextHigh, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CostLine(label: String, amount: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextHigh, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("%.0f".format(amount), color = if (bold) TextHigh else TextMid, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun BreakLine(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextHigh, fontSize = 14.sp)
        Text(count.toString(), color = TextMid, fontSize = 14.sp)
    }
}
