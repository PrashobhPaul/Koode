package com.trippulse.app.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trippulse.app.R
import com.trippulse.app.core.CrashLog
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

/** Shown on Home after a crash, so the cause can actually be found and fixed. */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    val colors = KoodeTheme.colors
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val details = report ?: return
    KoodeCard(accent = colors.warn) {
        Text(stringResource(R.string.t_koode_closed_unexpectedly_last_time_e8f0a), color = colors.warn, style = MaterialTheme.typography.titleMedium)
        Text(
            "Your journeys are safe. Sharing the technical details (no locations, just the error) helps fix the cause.",
            color = colors.textMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.weight(1f)) {
                SecondaryButton(stringResource(R.string.t_share_details_981b8), {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Koode crash details")
                        .putExtra(Intent.EXTRA_TEXT, details)
                    context.startActivity(Intent.createChooser(send, "Share crash details"))
                }, height = 42.dp)
            }
            Box(Modifier.weight(1f)) {
                SecondaryButton(stringResource(R.string.t_dismiss_70afe), { CrashLog.clear(context); report = null }, accent = colors.textMid, height = 42.dp)
            }
        }
    }
}
