package com.trippulse.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import com.trippulse.app.domain.Market
import com.trippulse.app.domain.Markets

/**
 * The traveller's market, available to every screen without plumbing: what
 * the emergency number is, whether tolls can be noticed here, what a
 * booking reference is called. Provided once at the root from AppGraph.market().
 */
val LocalMarket = compositionLocalOf<Market> { Markets.GENERIC }
