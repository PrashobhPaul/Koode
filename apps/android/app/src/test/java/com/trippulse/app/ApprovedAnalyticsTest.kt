package com.trippulse.app

import com.trippulse.app.domain.ApprovedAnalytics
import com.trippulse.app.domain.EventTypes
import com.trippulse.app.domain.FollowerAlerts
import com.trippulse.app.domain.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What followers may learn when a journey is approved: distances, times,
 * stops. Never money — and the same rule the server enforces agrees.
 */
class ApprovedAnalyticsTest {

    private val summary = TripSummary(
        distanceKm = 318.4, drivingSeconds = 21_600, totalSeconds = 27_000, stops = 4,
        foodBreaks = 1, waterConfirmations = 3, toiletBreaks = 1, restBreaks = 1, fuelStops = 1,
        teaCoffee = 2, snacks = 1, longestLegSeconds = 7_200, longestBreakSeconds = 1_800, days = 1
    )

    private fun built() = ApprovedAnalytics.build(
        summary, "Home", "Thrissur", 1_759_000_000_000L, 1_759_027_000_000L, "CAR", tollsCrossed = 6
    )

    @Test fun the_approved_analytics_carry_no_money() {
        val doc = built()
        assertFalse(ApprovedAnalytics.isFinancial(doc))
        assertEquals(318.4, doc["distanceKm"])
        assertEquals(6, doc["tollsCrossed"])
        assertEquals("Thrissur", doc["destination"])
    }

    @Test fun anything_money_shaped_is_caught_at_any_depth() {
        assertTrue(ApprovedAnalytics.isFinancial(built() + ("fuelCost" to 2800)))
        assertTrue(ApprovedAnalytics.isFinancial(mapOf("summary" to mapOf("expenses" to listOf(1)))))
        assertTrue(ApprovedAnalytics.isFinancial(mapOf("legs" to listOf(mapOf("taxiFare" to 650)))))
        assertTrue(ApprovedAnalytics.isFinancial(mapOf("note" to "spent ₹450")))
        assertTrue(ApprovedAnalytics.isFinancial(mapOf("fastagBalance" to 1240)))
    }

    @Test fun the_report_ends_the_journey_for_followers_only_once_approved() {
        // The report announcement is a follower notification of its own...
        assertTrue(FollowerAlerts.shouldNotify(EventTypes.JOURNEY_REPORT_AVAILABLE, emptyMap()))
        // ...that replaces the completion notice instead of ringing twice.
        assertEquals(
            FollowerAlerts.notificationId("key-1", EventTypes.TRIP_COMPLETED, 1L, emptyMap()),
            FollowerAlerts.notificationId("key-1", EventTypes.JOURNEY_REPORT_AVAILABLE, 2L, emptyMap())
        )
    }
}
