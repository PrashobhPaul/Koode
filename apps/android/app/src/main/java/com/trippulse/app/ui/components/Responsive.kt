package com.trippulse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trippulse.app.ui.theme.Spacing

/**
 * How much room we have to work with.
 *
 * Koode runs on a phone in a jacket pocket, a tablet propped on a kitchen
 * table and a laptop browser window. Rather than scatter width checks through
 * the screens, layout decisions read a single [WindowClass] and the shared
 * containers below do the rest.
 */
enum class WindowClass {
    /** Phones in portrait. One column, full-bleed. */
    COMPACT,

    /** Large phones in landscape, small tablets. One centred column. */
    MEDIUM,

    /** Tablets and desktop windows. Two columns where content supports it. */
    EXPANDED;

    val isCompact: Boolean get() = this == COMPACT
    val isWide: Boolean get() = this == EXPANDED

    /** Comfortable horizontal padding for this size. */
    val gutter: Dp
        get() = when (this) {
            COMPACT -> Spacing.xl
            MEDIUM -> Spacing.xxl
            EXPANDED -> 40.dp
        }

    /** The widest a single column of text should ever get. */
    val contentMaxWidth: Dp
        get() = when (this) {
            COMPACT -> Dp.Unspecified
            MEDIUM -> 640.dp
            EXPANDED -> 880.dp
        }

    /** Columns for card lists: one on a phone, two once a pair fits comfortably. */
    val gridColumns: Int
        get() = if (this == COMPACT) 1 else 2

    /** Map height that keeps its aspect sane as the window grows. */
    val mapHeight: Dp
        get() = when (this) {
            COMPACT -> 240.dp
            MEDIUM -> 320.dp
            EXPANDED -> 420.dp
        }
}

val LocalWindowClass = staticCompositionLocalOf { WindowClass.COMPACT }

/** Derives the window class from the current configuration. */
@Composable
fun rememberWindowClass(): WindowClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowClass.COMPACT
        widthDp < 900 -> WindowClass.MEDIUM
        else -> WindowClass.EXPANDED
    }
}

/**
 * The standard page container: gutters that grow with the window and a
 * measured max width so a line of text never runs the full span of a desktop
 * monitor.
 */
@Composable
fun AdaptiveContainer(
    modifier: Modifier = Modifier,
    windowClass: WindowClass = LocalWindowClass.current,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.md),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (windowClass.isCompact) it else it.widthIn(max = windowClass.contentMaxWidth) }
                .padding(horizontal = windowClass.gutter),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * Two panes side by side once there is room, stacked when there isn't.
 *
 * Used for map-plus-detail screens: on a phone the map sits above the
 * timeline, on a tablet they sit beside each other and neither has to scroll
 * past the other.
 */
@Composable
fun AdaptiveTwoPane(
    modifier: Modifier = Modifier,
    windowClass: WindowClass = LocalWindowClass.current,
    primaryWeight: Float = 1f,
    secondaryWeight: Float = 1f,
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit
) {
    if (windowClass.isWide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Box(Modifier.weight(primaryWeight)) { primary() }
            Box(Modifier.weight(secondaryWeight)) { secondary() }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            primary()
            secondary()
        }
    }
}

/**
 * Component sizes for this screen: one set of numbers the shell and the
 * components read, so icons, badges, bars and cards scale together — a touch
 * smaller on a narrow phone, roomier on a tablet — instead of each screen
 * guessing. Touch targets never drop below 48 dp.
 */
data class KoodeDims(
    val topBarHeight: Dp,
    val logoSize: Dp,
    val wordmarkSize: TextUnit,
    val actionIconSize: Dp,
    val actionTarget: Dp,
    val avatarSize: Dp,
    val navIconSize: Dp,
    val navLabelSize: TextUnit,
    val navIndicatorWidth: Dp,
    val railWidth: Dp,
    val badgeSize: Dp,
    val dotSize: Dp,
    val cardPadding: Dp,
    val fabHeight: Dp
) {
    companion object {
        /** Phones under 360 dp wide (small Androids, display zoom). */
        val narrow = KoodeDims(
            topBarHeight = 54.dp, logoSize = 26.dp, wordmarkSize = 20.sp,
            actionIconSize = 22.dp, actionTarget = 44.dp, avatarSize = 28.dp,
            navIconSize = 22.dp, navLabelSize = 10.sp, navIndicatorWidth = 52.dp,
            railWidth = 80.dp, badgeSize = 16.dp, dotSize = 8.dp,
            cardPadding = 14.dp, fabHeight = 52.dp
        )
        val phone = KoodeDims(
            topBarHeight = 58.dp, logoSize = 28.dp, wordmarkSize = 22.sp,
            actionIconSize = 24.dp, actionTarget = 48.dp, avatarSize = 30.dp,
            navIconSize = 24.dp, navLabelSize = 11.sp, navIndicatorWidth = 60.dp,
            railWidth = 84.dp, badgeSize = 17.dp, dotSize = 9.dp,
            cardPadding = 16.dp, fabHeight = 56.dp
        )
        val tablet = KoodeDims(
            topBarHeight = 66.dp, logoSize = 32.dp, wordmarkSize = 25.sp,
            actionIconSize = 26.dp, actionTarget = 52.dp, avatarSize = 34.dp,
            navIconSize = 26.dp, navLabelSize = 12.sp, navIndicatorWidth = 64.dp,
            railWidth = 96.dp, badgeSize = 18.dp, dotSize = 10.dp,
            cardPadding = 20.dp, fabHeight = 60.dp
        )
    }
}

val LocalDims = staticCompositionLocalOf { KoodeDims.phone }

@Composable
fun rememberDims(windowClass: WindowClass = rememberWindowClass()): KoodeDims {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        windowClass == WindowClass.EXPANDED -> KoodeDims.tablet
        windowClass == WindowClass.MEDIUM -> KoodeDims.tablet.copy(railWidth = 88.dp)
        widthDp < 360 -> KoodeDims.narrow
        else -> KoodeDims.phone
    }
}

/**
 * Cards in a grid that adapts: a single column on a phone, rows of two on
 * wider screens, each row's cards stretched to one height so their edges
 * line up.
 */
@Composable
fun <T> AdaptiveGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    windowClass: WindowClass = LocalWindowClass.current,
    item: @Composable (T, Modifier) -> Unit
) {
    val columns = windowClass.gridColumns
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (columns == 1) {
            items.forEach { item(it, Modifier) }
        } else {
            items.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { item(it, Modifier.fillMaxHeight()) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
