package com.trippulse.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.coroutineContext

/**
 * The lock every change to a live journey goes through, which also knows who
 * holds it and since when.
 *
 * A plain mutex fails silently: if one holder ever waits on something that
 * never answers, every location, heartbeat and break log queues behind it and
 * the journey freezes for its followers while the app looks perfectly alive.
 * Nothing legitimate holds this lock for minutes, so a holder stuck past
 * [STUCK_MS] is cancelled by [healIfStuck] and the journey carries on: one
 * update is lost instead of the rest of the trip.
 *
 * Same call shape as `Mutex.withLock`, so call sites read the same.
 */
class JourneyLock {
    @PublishedApi internal val mutex = Mutex()
    @PublishedApi @Volatile internal var heldSinceMs: Long = 0L
    @PublishedApi @Volatile internal var holder: Job? = null

    suspend inline fun <T> withLock(action: () -> T): T {
        mutex.lock()
        heldSinceMs = System.currentTimeMillis()
        holder = coroutineContext[Job]
        try {
            return action()
        } finally {
            holder = null
            heldSinceMs = 0L
            mutex.unlock()
        }
    }

    /** How long the current holder has had the lock, or 0 when it is free. */
    fun heldForMs(nowMs: Long): Long = heldSinceMs.takeIf { it > 0 }?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L

    /**
     * Cancels a holder that has had the lock for longer than [STUCK_MS].
     * Returns true when it did. Cancellation releases the lock as soon as the
     * holder reaches its next suspension point; every network call made under
     * the lock has a hard deadline, so that point always comes.
     */
    fun healIfStuck(nowMs: Long): Boolean {
        if (heldForMs(nowMs) <= STUCK_MS) return false
        val stuck = holder ?: return false
        stuck.cancel(CancellationException("Journey lock held for ${heldForMs(nowMs) / 1000} s"))
        return true
    }

    companion object {
        /** Far longer than any real step (a journey close uploads a report in well under a minute). */
        const val STUCK_MS = 5 * 60_000L
    }
}
