package com.trippulse.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * The lock every change to a live journey goes through, which also knows who
 * holds it and since when.
 *
 * A plain mutex fails silently: if one holder ever waits on something that
 * never answers, every location, heartbeat, break log, halt and expense
 * queues behind it and the journey freezes for its followers while the app
 * looks perfectly alive. Nothing legitimate holds this lock for more than a
 * moment, so:
 *
 *  - a caller that has waited [WAIT_MS] for a holder that has had the lock at
 *    least that long cancels the holder and takes the lock — the traveller's
 *    tap is saved, on the phone first, within seconds, whatever else is going
 *    on;
 *  - a watchdog ([healIfStuck]) does the same for a holder stuck past
 *    [STUCK_MS] even when nothing else is waiting.
 *
 * Cancellation releases the lock at the holder's next suspension point; every
 * network call made under the lock has a hard deadline, so that point always
 * comes. One update is lost instead of the rest of the trip.
 *
 * Same call shape as `Mutex.withLock`, so call sites read the same.
 */
class JourneyLock {
    @PublishedApi internal val mutex = Mutex()
    @PublishedApi @Volatile internal var heldSinceMs: Long = 0L
    @PublishedApi @Volatile internal var holder: Job? = null

    suspend inline fun <T> withLock(action: () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            holder = null
            heldSinceMs = 0L
            mutex.unlock()
        }
    }

    /** Takes the lock, evicting a holder that has kept everyone waiting too long. */
    @PublishedApi
    internal suspend fun acquire() {
        if (withTimeoutOrNull(WAIT_MS) { mutex.lock() } == null) {
            healIfStuck(System.currentTimeMillis(), WAIT_MS)
            mutex.lock()
        }
        heldSinceMs = System.currentTimeMillis()
        holder = coroutineContext[Job]
    }

    /** How long the current holder has had the lock, or 0 when it is free. */
    fun heldForMs(nowMs: Long): Long = heldSinceMs.takeIf { it > 0 }?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L

    /**
     * Cancels a holder that has had the lock for longer than [stuckAfterMs].
     * Returns true when it did.
     */
    fun healIfStuck(nowMs: Long, stuckAfterMs: Long = STUCK_MS): Boolean {
        if (heldForMs(nowMs) <= stuckAfterMs) return false
        val stuck = holder ?: return false
        stuck.cancel(CancellationException("Journey lock held for ${heldForMs(nowMs) / 1000} s"))
        return true
    }

    companion object {
        /** How long a tap may wait behind another step before that step is given up on. */
        const val WAIT_MS = 10_000L
        /** How long a step may hold the lock unattended; far longer than any real one. */
        const val STUCK_MS = 60_000L
    }
}
