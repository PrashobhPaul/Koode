package com.trippulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trippulse.app.ui.theme.BodyFamily
import com.trippulse.app.ui.theme.DisplayFamily
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing
import kotlinx.coroutines.delay

private val SosGround = Color(0xFF1A0A0D)
private val SosCard = Color(0xFF2A1116)
private val SosLine = Color(0xFF4A1D24)
private val SosInk = Color(0xFFFFF1F1)
private val SosInkSoft = Color(0xFFF3C4C4)
private val SosRing = Color(0xFFF87171)
private val SosButton = Color(0xFFC81E1E)

/**
 * The one SOS control on the journey screen: a clear, full-width button. A
 * tap opens [SosCountdown] — nothing is sent from the button itself.
 */
@Composable
fun SosButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(if (enabled) SosButton else SosCard)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(KoodeIcons.Alert, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(Spacing.sm))
        Text(
            if (enabled) "SOS" else "SOS is active",
            color = Color.White, fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp
        )
    }
}

/**
 * The SOS confirmation: a short countdown the traveller can cancel, showing
 * exactly who will be alerted, with "Send now" for when every second counts.
 *
 * An accidental SOS gives the people watching a real fright, and a missed
 * one is worse — so sending is automatic when the count runs out (a
 * traveller in trouble may not be able to press anything), and cancelling is
 * one large, unmistakable button.
 *
 * @param recipients names of the people who will be alerted; empty when
 *   the list isn't known, in which case no number is claimed.
 */
@Composable
fun SosCountdown(
    recipients: List<String>,
    seconds: Int = 5,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var left by remember { mutableIntStateOf(seconds) }
    var fired by remember { mutableStateOf(false) }

    fun send() {
        if (fired) return
        fired = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onSend()
    }

    LaunchedEffect(Unit) {
        while (left > 0 && !fired) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(1_000)
            left--
        }
        send()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(SosGround)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SOS", color = SosRing, fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp)
                Text(
                    "Sending your SOS", color = SosInk, fontFamily = DisplayFamily,
                    fontWeight = FontWeight.Bold, fontSize = 28.sp, textAlign = TextAlign.Center
                )
                Text(
                    "Your live location and an alert go to the people following your journey. Cancel if you pressed it by mistake.",
                    color = SosInkSoft, fontFamily = BodyFamily, fontSize = 15.sp, lineHeight = 21.sp,
                    textAlign = TextAlign.Center
                )
            }

            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                val fraction = left.toFloat() / seconds
                Canvas(Modifier.size(188.dp)) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2
                    drawCircle(color = Color(0x1AEF4444), radius = size.minDimension / 2 + 16.dp.toPx())
                    drawArc(
                        color = Color(0xFF3B1419), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke)
                    )
                    drawArc(
                        color = SosRing, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                ) {
                    Text("$left", color = SosInk, fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 76.sp)
                    Text(if (left == 1) "second" else "seconds", color = SosInkSoft, fontFamily = BodyFamily, fontSize = 13.sp)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(SosCard)
                    .border(1.dp, SosLine, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (recipients.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                        recipients.take(3).forEach { n ->
                            PersonAvatar(n, 40.dp, Modifier.border(2.dp, SosCard, CircleShape))
                        }
                        if (recipients.size > 3) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(SosLine),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+${recipients.size - 3}", color = SosInk, fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                    val n = recipients.size
                    Text(
                        "$n ${if (n == 1) "person" else "people"} will be alerted, even with their phone locked",
                        color = SosInk, fontFamily = BodyFamily, fontSize = 15.sp, textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "Everyone following your journey will be alerted",
                        color = SosInk, fontFamily = BodyFamily, fontSize = 15.sp, textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SosButton)
                        .clickable(role = Role.Button) { send() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Send now", color = Color.White, fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.5.dp, SosInkSoft, RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button) { if (!fired) onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Cancel — I'm okay", color = SosInk, fontFamily = BodyFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
    }
}
