package com.trippulse.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.trippulse.app.BuildConfig
import com.trippulse.app.ui.Links
import com.trippulse.app.ui.components.KoodeCard
import com.trippulse.app.ui.components.KoodeLockup
import com.trippulse.app.ui.components.KoodeNightSky
import com.trippulse.app.ui.components.SectionHeader
import com.trippulse.app.ui.theme.KoodeTheme
import com.trippulse.app.ui.theme.Spacing

/**
 * The hidden About.
 *
 * There is no menu item for it — you reach it by tapping the Koode mark in the
 * home header, the small delight of a logo that does something. It is the one
 * place the full brand lives inside the app: the lockup over the night sky,
 * what Koode is and is not, the promise it makes about a family's data, and the
 * ways out to the web viewer, the app and the source.
 */
@Composable
fun AboutScreen(nav: NavHostController) {
    val colors = KoodeTheme.colors
    val uri = LocalUriHandler.current

    BackHandler { nav.popBackStack() }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val heroAlpha by animateFloatAsState(
        if (shown) 1f else 0f, tween(700, easing = EaseOutCubic), label = "aboutHero"
    )
    val heroScale by animateFloatAsState(
        if (shown) 1f else 0.9f, tween(700, easing = EaseOutCubic), label = "aboutHeroScale"
    )

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ---- hero: the lockup over the night sky ----
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                KoodeNightSky(Modifier.fillMaxSize())
                // back affordance, over the hero
                BackButton(
                    onClick = { nav.popBackStack() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(Spacing.md)
                )
                KoodeLockup(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .alpha(heroAlpha)
                        .scale(heroScale),
                    glyphSize = 116.dp,
                    wordmarkSize = 44.sp,
                    showTagline = true
                )
            }

            Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
                // version
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}",
                        color = colors.textLow,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.height(Spacing.lg))

                KoodeCard(title = "What Koode is") {
                    Text(
                        "A journey companion that keeps the people you love informed about " +
                            "your journey, wellbeing and safety — without you having to call or " +
                            "message them. It speaks up when something looks wrong, and stays " +
                            "quiet, but present, when everything is fine.",
                        color = colors.textMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                KoodeCard(title = "The promise", accent = colors.accent) {
                    PromiseLine("Your location is shared only during a journey you start.")
                    PromiseLine("Only with the people you approve.")
                    PromiseLine("The shared copy self-destructs shortly after the journey ends.")
                    PromiseLine("Your profile, contacts, history and money stay on this phone.")
                    PromiseLine("No ads, no analytics, no accounts.")
                }
                Spacer(Modifier.height(Spacing.md))

                SectionHeader("Follow, get, or read the code")
                Spacer(Modifier.height(Spacing.sm))
                LinkRow("🌐", "Open the web viewer", "Follow a journey in any browser") {
                    uri.openUri(Links.WEB_VIEWER)
                }
                LinkRow("⬇️", "Get the app", "Install or update Koode") {
                    uri.openUri(Links.APK)
                }
                LinkRow("📄", "Privacy policy", "What we collect, and what we never do") {
                    uri.openUri("${Links.REPO}/blob/main/docs/PRIVACY.md")
                }
                LinkRow("📜", "Terms of use", "The short, plain version") {
                    uri.openUri("${Links.REPO}/blob/main/docs/TERMS.md")
                }
                LinkRow("⭐", "Project on GitHub", "The source, open to read") {
                    uri.openUri(Links.REPO)
                }

                Spacer(Modifier.height(Spacing.xl))
                Text(
                    "Made with care for the people you travel home to.",
                    color = colors.textLow,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }
}

@Composable
private fun PromiseLine(text: String) {
    val colors = KoodeTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
        Spacer(Modifier.size(Spacing.sm))
        Text(text, color = colors.textMid, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkRow(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = KoodeTheme.colors
    KoodeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.size(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.textHigh, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = colors.textLow, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = colors.textLow, fontSize = 22.sp)
        }
    }
    Spacer(Modifier.height(Spacing.sm))
}

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x33000000))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("‹", color = Color(0xFFF2FAFC), fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}
