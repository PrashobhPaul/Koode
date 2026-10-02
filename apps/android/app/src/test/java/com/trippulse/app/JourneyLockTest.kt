package com.trippulse.app

import com.trippulse.app.data.JourneyLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyLockTest {

    @Test fun releasesOnNormalReturnAndOnEarlyReturn() = runBlocking {
        val lock = JourneyLock()
        assertEquals(1, lock.withLock { 1 })
        fun early(): Int = 0
        val r = lock.withLock { if (true) early() else 2 }
        assertEquals(0, r)
        assertEquals(0L, lock.heldForMs(System.currentTimeMillis()))
        // Free again: a second section runs straight away.
        withTimeout(1_000) { lock.withLock { } }
    }

    @Test fun releasesWhenTheSectionThrows() = runBlocking {
        val lock = JourneyLock()
        runCatching { lock.withLock { error("boom") } }
        withTimeout(1_000) { lock.withLock { } }
    }

    @Test fun aFreshHolderIsLeftAlone() = runBlocking {
        val lock = JourneyLock()
        val entered = CompletableDeferred<Unit>()
        val job = launch { lock.withLock { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        assertFalse(lock.healIfStuck(System.currentTimeMillis()))
        job.cancel()
    }

    /** The bug this exists for: a holder that never returns used to freeze every later update. */
    @Test fun aStuckHolderIsCancelledAndTheJourneyCarriesOn() = runBlocking {
        val lock = JourneyLock()
        val entered = CompletableDeferred<Unit>()
        val stuck = launch { lock.withLock { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val waiting = async { lock.withLock { "next update" } }
        val later = System.currentTimeMillis() + JourneyLock.STUCK_MS + 1_000
        assertTrue(lock.heldForMs(later) > JourneyLock.STUCK_MS)
        assertTrue(lock.healIfStuck(later))
        assertEquals("next update", withTimeout(2_000) { waiting.await() })
        assertTrue(stuck.isCancelled)
    }
}
