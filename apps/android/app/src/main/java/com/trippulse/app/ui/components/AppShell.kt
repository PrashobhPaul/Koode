package com.trippulse.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.theme.DisplayFamily
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Motion
import com.trippulse.app.ui.theme.Radii
import com.trippulse.app.ui.theme.Spacing

/**
 * The app shell — the frame every tab sits in, modelled on the global apps
 * people already know by feel:
 *
 *   top      the Koode mark and wordmark on the left, actions on the right
 *   bottom   icon + label tabs with badges (phones)
 *   side     a navigation rail with the primary action on top (tablets,
 *            phones in landscape)
 *
 * Sizes come from [LocalDims]; motion from [Motion]; touch feedback from
 * [rememberHaptics]. Screens never build their own chrome.
 */

/** A badge on a tab or an action: a count, or a plain dot when [count] is 0. */
data class NavBadge(val count: Int, val color: Color)

data class NavTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val badge: NavBadge? = null
)

// ---------------------------------------------------------------------------
// Badges
// ---------------------------------------------------------------------------

/** Draws [badge] over the top-end corner of [content]; pops in and out. */
@Composable
fun BadgedIcon(
    badge: NavBadge?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dims = LocalDims.current
    val colors = KoodeTheme.colors
    // Keep the last badge while it animates out.
    var shown by remember { mutableStateOf(badge) }
    if (badge != null) shown = badge
    Box(modifier) {
        content()
        AnimatedVisibility(
            visible = badge != null,
            enter = scaleIn(tween(Motion.normal)) + fadeIn(tween(Motion.normal)),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(
                    x = if ((shown?.count ?: 0) > 0) dims.badgeSize * 0.55f else dims.dotSize * 0.35f,
                    y = if ((shown?.count ?: 0) > 0) -dims.badgeSize * 0.35f else -dims.dotSize * 0.2f
                )
        ) {
            val b = shown ?: return@AnimatedVisibility
            if (b.count > 0) {
                Box(
                    Modifier
                        .heightIn(min = dims.badgeSize)
                        .widthIn(min = dims.badgeSize)
                        .clip(RoundedCornerShape(Radii.pill))
                        .background(b.color)
                        .border(1.5.dp, colors.backgroundElevated, RoundedCornerShape(Radii.pill))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (b.count > 99) "99+" else b.count.toString(),
                        color = if (b.color.luminance() > 0.45f) Color(0xFF07131D) else Color.White,
                        style = TextStyle(fontSize = (dims.badgeSize.value * 0.62f).sp, fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(dims.dotSize)
                        .clip(CircleShape)
                        .background(b.color)
                        .border(1.5.dp, colors.backgroundElevated, CircleShape)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

/** One round action in the top bar, with the platform ripple and a click. */
@Composable
fun TopBarAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    badge: NavBadge? = null
) {
    val dims = LocalDims.current
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(dims.actionTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) {
                haptics.click()
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        BadgedIcon(badge) {
            Icon(icon, contentDescription = null, tint = KoodeTheme.colors.textHigh, modifier = Modifier.size(dims.actionIconSize))
        }
    }
}

/**
 * The top bar: mark + "Koode" on the left (a tap opens About), [actions] on
 * the right. [elevated] once content has scrolled beneath it, so the bar
 * separates from the page only when it needs to.
 */
@Composable
fun KoodeTopBar(
    elevated: Boolean,
    onBrand: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit
) {
    val dims = LocalDims.current
    val colors = KoodeTheme.colors
    val bg by animateColorAsState(
        if (elevated) colors.backgroundElevated else colors.background,
        tween(Motion.normal), label = "topBarBg"
    )
    val hairline by animateFloatAsState(if (elevated) 1f else 0f, tween(Motion.normal), label = "topBarLine")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val brandScale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.6f), label = "brandPress")

    Column(modifier.fillMaxWidth().background(bg)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            Modifier
                .fillMaxWidth()
                .height(dims.topBarHeight)
                .padding(start = Spacing.lg, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .scale(brandScale)
                    .clip(RoundedCornerShape(Radii.sm))
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "About Koode", onClick = onBrand)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KoodeMarkTile(size = dims.logoSize, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Koode",
                    color = colors.textHigh,
                    style = TextStyle(
                        fontFamily = DisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = dims.wordmarkSize,
                        letterSpacing = (-0.6).sp
                    )
                )
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outline.copy(alpha = 0.6f * hairline))
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom bar (phones)
// ---------------------------------------------------------------------------

@Composable
fun KoodeBottomBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KoodeTheme.colors
    Column(modifier.fillMaxWidth().background(colors.backgroundElevated)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline.copy(alpha = 0.5f)))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                NavItem(tab, index == selected, { onSelect(index) }, Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Navigation rail (tablets, landscape)
// ---------------------------------------------------------------------------

@Composable
fun KoodeNavRail(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {}
) {
    val dims = LocalDims.current
    val colors = KoodeTheme.colors
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(dims.railWidth)
                .fillMaxHeight()
                .background(colors.background)
                .navigationBarsPadding()
                .padding(top = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            header()
            Spacer(Modifier.height(Spacing.xs))
            tabs.forEachIndexed { index, tab ->
                NavItem(tab, index == selected, { onSelect(index) }, Modifier.fillMaxWidth())
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outline.copy(alpha = 0.5f)))
    }
}

/**
 * One destination: the icon sits on an indicator pill that grows in when
 * selected, swaps to its solid form with a small spring, and the label firms
 * up. Selection gives a click under the finger.
 */
@Composable
private fun NavItem(tab: NavTab, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val dims = LocalDims.current
    val colors = KoodeTheme.colors
    val haptics = rememberHaptics()
    val tint by animateColorAsState(
        if (isSelected) colors.textHigh else colors.textMid,
        tween(Motion.normal), label = "navTint"
    )
    val iconTint by animateColorAsState(
        if (isSelected) colors.accent else colors.textMid,
        tween(Motion.normal), label = "navIconTint"
    )
    val pillWidth by animateDpAsState(
        if (isSelected) dims.navIndicatorWidth else dims.navIndicatorWidth * 0.6f,
        tween(Motion.normal), label = "navPill"
    )
    val pillAlpha by animateFloatAsState(if (isSelected) 1f else 0f, tween(Motion.normal), label = "navPillAlpha")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, tween(Motion.fast), label = "navPress")

    Column(
        modifier
            .selectable(
                selected = isSelected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = {
                    haptics.click()
                    onClick()
                }
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.height(dims.navIconSize + 6.dp).width(dims.navIndicatorWidth),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(pillWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(colors.accent.copy(alpha = 0.11f * pillAlpha))
            )
            BadgedIcon(tab.badge, Modifier.scale(press)) {
                Icon(
                    if (isSelected) tab.selectedIcon else tab.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(dims.navIconSize)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = dims.navLabelSize,
                letterSpacing = 0.1.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Primary action
// ---------------------------------------------------------------------------

/**
 * The floating primary action. Extended (icon + label) at rest; shrinks to
 * its icon while the page scrolls down so it stops covering content, and
 * grows back on the way up. [shape] lets the rail use a rounded square.
 */
@Composable
fun KoodeFab(
    label: String,
    icon: @Composable () -> Unit,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.pill)
) {
    val dims = LocalDims.current
    val colors = KoodeTheme.colors
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f), label = "fabPress")
    val container = if (enabled) colors.accent else colors.surfaceRaised
    val ink = if (!enabled) colors.textMid else if (colors.isDark) Color(0xFF07131D) else Color.White

    Row(
        modifier
            .scale(scale)
            .shadow(
                elevation = if (enabled) 10.dp else 2.dp,
                shape = shape,
                ambientColor = colors.accent,
                spotColor = colors.accent
            )
            .clip(shape)
            .background(container)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label) {
                haptics.click()
                onClick()
            }
            .semantics { contentDescription = label }
            .heightIn(min = dims.fabHeight)
            .padding(horizontal = (dims.fabHeight - 24.dp) / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides ink
            ) { icon() }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(tween(Motion.normal)) + fadeIn(tween(Motion.normal)),
            exit = shrinkHorizontally(tween(Motion.normal)) + fadeOut(tween(Motion.fast))
        ) {
            Row {
                Spacer(Modifier.width(Spacing.sm))
                Text(label, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

/** One thing that needs a look, shown under the bell. */
data class ActivityItem(
    val key: String,
    val title: String,
    val detail: String,
    val color: Color,
    val onOpen: () -> Unit
)

/** The bell's sheet: everything waiting on you, most urgent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySheet(items: List<ActivityItem>, onDismiss: () -> Unit) {
    val colors = KoodeTheme.colors
    val dims = LocalDims.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.backgroundElevated
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text("Activity", color = colors.textHigh, style = MaterialTheme.typography.headlineSmall)
            if (items.isEmpty()) {
                Text(
                    "You're all caught up. Anything that needs you — a journey to review, someone you follow who needs a look — shows up here.",
                    color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                )
            }
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.md))
                        .background(colors.surface)
                        .clickable {
                            onDismiss()
                            item.onOpen()
                        }
                        .padding(dims.cardPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(item.color))
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                        Text(item.detail, color = colors.textMid, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Icon(KoodeIcons.Chevron, contentDescription = null, tint = colors.textLow, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Secondary screens
// ---------------------------------------------------------------------------

/** The back control at the top of a pushed screen: round, rippled, clicked. */
@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dims = LocalDims.current
    val haptics = rememberHaptics()
    Box(
        modifier
            .offset(x = -Spacing.md)
            .size(dims.actionTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Back") {
                haptics.click()
                onBack()
            }
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center
    ) {
        Icon(KoodeIcons.Back, contentDescription = null, tint = KoodeTheme.colors.textHigh, modifier = Modifier.size(dims.actionIconSize))
    }
}
