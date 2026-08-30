package com.trippulse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
            MEDIUM -> 620.dp
            EXPANDED -> 720.dp
        }

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
