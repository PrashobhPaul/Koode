package com.trippulse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.trippulse.app.core.InputRules
import com.trippulse.app.core.TripCredentials
import com.trippulse.app.ui.JoinVm
import com.trippulse.app.ui.Routes
import com.trippulse.app.ui.components.AdaptiveContainer
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.PrimaryButton
import com.trippulse.app.ui.components.SecondaryButton
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing

/**
 * Following someone's journey.
 *
 * The whole screen is built around one observation: the people who use it most
 * are the ones least comfortable with phones. So there is nothing here to get
 * wrong. The journey number is digits — the "TP-" is printed by the app, not
 * typed — and the passcode is six digits. No dashes, no underscores, no case,
 * nothing a paste can clip.
 *
 * The passcode is genuinely optional, and the screen now says what actually
 * happens either way: with it you're in immediately; without it the traveller
 * gets a request and this screen waits. Previously the waiting state surfaced
 * as a network error, which is why "it says password is optional but silently
 * fails" was exactly right.
 */
@Composable
fun JoinViewerScreen(nav: NavHostController) {
    val vm: JoinVm = viewModel(factory = JoinVm.Factory)
    val colors = KoodeTheme.colors
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val awaiting by vm.awaitingApproval.collectAsStateWithLifecycle()

    var code by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var viewerName by remember { mutableStateOf("") }

    val codeComplete = TripCredentials.isCompleteCode(code)
    val canSubmit = code.isNotBlank() &&
        (TripCredentials.isCompletePasscode(passcode) || viewerName.isNotBlank())

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(Spacing.lg))
        AdaptiveContainer {
            Text("Follow a journey", color = colors.textHigh, style = MaterialTheme.typography.displaySmall)
            Text(
                "Type the number your traveller shared. If they gave you a 6-digit passcode too, " +
                    "you're in straight away — otherwise they'll get a request to let you in.",
                color = colors.textMid, style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(Spacing.sm))

            // ---- journey number: prefix is ours, digits are theirs ----
            KoodeCard(title = "Journey number") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(colors.surfaceRaised)
                            .padding(horizontal = 14.dp, vertical = 16.dp)
                    ) {
                        Text(
                            TripCredentials.PREFIX,
                            color = colors.accent,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = InputRules.digits(it, TripCredentials.CODE_LENGTH) },
                        placeholder = { Text("40381927") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    if (codeComplete) "Looks right."
                    else "${TripCredentials.CODE_LENGTH} digits — numbers only, no dashes.",
                    color = if (codeComplete) colors.accent else colors.textLow,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            KoodeCard(title = "Passcode (optional)") {
                OutlinedTextField(
                    value = passcode,
                    onValueChange = { passcode = InputRules.digits(it, TripCredentials.PASSCODE_LENGTH) },
                    placeholder = { Text("6 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (passcode.isBlank())
                        "Leave this empty and we'll ask the traveller to approve you by name."
                    else if (TripCredentials.isCompletePasscode(passcode))
                        "Ready — you'll go straight in."
                    else "${TripCredentials.PASSCODE_LENGTH} digits, or leave it empty.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            KoodeCard(title = "Your name") {
                OutlinedTextField(
                    value = viewerName,
                    onValueChange = { viewerName = InputRules.itemText(it) },
                    placeholder = { Text("So they know who's asking") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (error != null) {
                KoodeCard(accent = colors.danger) {
                    Text(error!!, color = colors.danger, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (notice != null && error == null) {
                KoodeCard(accent = colors.accent) {
                    Text(notice!!, color = colors.accent, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!vm.cloudAvailable()) {
                Text(
                    "This build is in local mode — following someone needs the cloud backend configured.",
                    color = colors.textLow, style = MaterialTheme.typography.bodySmall
                )
            }

            if (awaiting) {
                KoodeCard(accent = colors.accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = colors.accent,
                            modifier = Modifier.height(18.dp).width(18.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            "Waiting for approval",
                            color = colors.accent, style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "They'll see \"${viewerName.ifBlank { "Someone" }}\" asking to follow. " +
                            "This screen opens the journey the moment they say yes.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    SecondaryButton("Cancel", { vm.cancelWaiting() }, accent = colors.textMid, height = 42.dp)
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            if (busy && !awaiting) {
                Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = colors.accent)
                }
            } else if (!awaiting) {
                PrimaryButton(
                    text = if (passcode.isBlank()) "Ask to follow" else "Follow journey",
                    onClick = {
                        vm.join(code, passcode, viewerName) { key ->
                            nav.navigate(Routes.viewer(key)) { popUpTo(Routes.HOME) }
                        }
                    },
                    enabled = canSubmit
                )
            }
            SecondaryButton("Back", { nav.popBackStack() }, accent = colors.textMid, height = 44.dp)

            if (saved.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Text("Recently followed", color = colors.textMid, style = MaterialTheme.typography.titleMedium)
                saved.forEach { v ->
                    KoodeCard(onClick = { nav.navigate(Routes.viewer(v.accessKey)) }) {
                        Text(v.label, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                        Text(v.tripId, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.scrollBottom))
        }
    }
}
