package com.trippulse.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Motion
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing

/**
 * The Koode component library.
 *
 * Every screen builds from these, which is what keeps the app feeling like one
 * product rather than eight screens that happen to share a colour. Each
 * component is theme-aware (it reads [KoodeTheme.colors], never a literal) and
 * carries its own motion, so a press or a state change animates identically
 * wherever it appears.
 */

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/**
 * The workhorse surface. A soft raised card with an optional eyebrow title,
 * an optional accent edge, and a press animation when it's tappable.
 */
@Composable
fun KoodeCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val colors = KoodeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "cardPress"
    )

    Box(
        modifier
            .fillMaxWidth()
            .scale(if (onClick == null) 1f else scale)
            .clip(RoundedCornerShape(Radii.lg))
            .background(colors.surface)
            .then(
                if (accent != null) Modifier.border(
                    BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(Radii.lg)
                ) else Modifier.border(
                    BorderStroke(1.dp, colors.outline.copy(alpha = 0.5f)), RoundedCornerShape(Radii.lg)
                )
            )
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction, indication = null, onClick = onClick
                ) else Modifier
            )
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    color = colors.textLow,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            content()
        }
    }
}

/**
 * A hero surface for the one thing that matters most on a screen — the live
 * journey card, the health verdict. Slightly richer than [KoodeCard], with a
 * gradient wash in the accent colour so it reads first without shouting.
 */
@Composable
fun KoodeHeroCard(
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val colors = KoodeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "heroPress"
    )
    Box(
        modifier
            .fillMaxWidth()
            .scale(if (onClick == null) 1f else scale)
            .clip(RoundedCornerShape(Radii.xl))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (colors.isDark) 0.22f else 0.14f),
                        colors.surface
                    )
                )
            )
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), RoundedCornerShape(Radii.xl))
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction, indication = null, onClick = onClick
                ) else Modifier
            )
            .padding(Spacing.xl)
    ) {
        Column(content = content)
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * The primary action. Scales down under the finger and, when the user has
 * haptics enabled, taps back — the small physical acknowledgement that makes
 * an Android app feel native rather than drawn.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color? = null,
    leading: String? = null,
    height: Dp = 54.dp
) {
    val colors = KoodeTheme.colors
    val tint = accent ?: colors.accent
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f), label = "btnPress"
    )
    val container by animateColorAsState(
        targetValue = if (enabled) tint else colors.surfaceRaised,
        animationSpec = tween(Motion.fast), label = "btnColor"
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .scale(scale)
            .clip(RoundedCornerShape(Radii.md))
            .background(container)
            .clickable(
                interactionSource = interaction, indication = null, enabled = enabled
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(leading, fontSize = 16.sp)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text,
                color = if (enabled) (if (colors.isDark) Color(0xFF07131D) else Color.White) else colors.textLow,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 15.sp
            )
        }
    }
}

/** A quieter action: outlined, same motion, no fill. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color? = null,
    leading: String? = null,
    height: Dp = 48.dp
) {
    val colors = KoodeTheme.colors
    val tint = accent ?: colors.accent
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.6f), label = "sbtnPress"
    )
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .scale(scale)
            .clip(RoundedCornerShape(Radii.md))
            .border(BorderStroke(1.dp, tint.copy(alpha = if (enabled) 0.55f else 0.2f)), RoundedCornerShape(Radii.md))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(leading, fontSize = 15.sp)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text,
                color = if (enabled) tint else colors.textLow,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** A selectable chip — the building block of every option row in the app. */
@Composable
fun KoodeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
    accent: Color? = null
) {
    val colors = KoodeTheme.colors
    val tint = accent ?: colors.accent
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f), label = "chipPress"
    )
    val bg by animateColorAsState(
        targetValue = if (selected) tint.copy(alpha = if (colors.isDark) 0.9f else 1f) else colors.surfaceRaised,
        animationSpec = tween(Motion.fast), label = "chipBg"
    )
    val fg = if (selected) (if (colors.isDark) Color(0xFF07131D) else Color.White) else colors.textMid

    Box(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(Radii.pill))
            .background(bg)
            .border(
                BorderStroke(1.dp, if (selected) Color.Transparent else colors.outline),
                RoundedCornerShape(Radii.pill)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Text(leading, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Status indicators
// ---------------------------------------------------------------------------

/**
 * A live status dot that breathes.
 *
 * The pulse is the point: a static dot says "this is a colour", a pulsing one
 * says "this is happening right now". Used for LIVE badges everywhere, and
 * mirrored by the map's traveller marker.
 */
@Composable
fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    active: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing), repeatMode = RepeatMode.Restart
        ),
        label = "pulsePhase"
    )
    Box(modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(size + (size * 1.3f * phase))
                    .alpha((1f - phase) * 0.45f)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

/** A small pill of status: "LIVE", "Safe", "Waiting for updates". */
@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false
) {
    val colors = KoodeTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(color.copy(alpha = if (colors.isDark) 0.16f else 0.12f))
            .padding(horizontal = Spacing.md, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (pulsing) {
            PulsingDot(color, size = 6.dp)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
    }
}

/** Section heading with the app's standard rhythm above and below. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = KoodeTheme.colors
    Row(
        modifier.fillMaxWidth().padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
        trailing?.invoke()
    }
}

/** A labelled figure — the unit the summary screen is built from. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    val colors = KoodeTheme.colors
    KoodeCard(modifier) {
        Text(label.uppercase(), color = colors.textLow, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            value,
            color = accent ?: colors.textHigh,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A key/value line, used across wellbeing, cost and detail lists. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leading: String? = null,
    emphasis: Boolean = false,
    valueColor: Color? = null
) {
    val colors = KoodeTheme.colors
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (leading != null) {
                Text(leading, fontSize = 15.sp)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                label,
                color = colors.textHigh,
                style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            value,
            color = valueColor ?: if (emphasis) colors.textHigh else colors.textMid,
            style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
        )
    }
}

/** An empty state that reads as a suggestion, not an error. */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    KoodeCard(modifier) {
        Text(emoji, fontSize = 26.sp)
        Spacer(Modifier.height(Spacing.sm))
        Text(title, color = colors.textHigh, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
    }
}
