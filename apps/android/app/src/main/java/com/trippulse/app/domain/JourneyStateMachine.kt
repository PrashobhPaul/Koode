package com.trippulse.app.domain

/**
 * Explicit, pure journey state machine (docs/spec/11, 79, 88).
 *
 * Connectivity is deliberately NOT modelled here — it is an orthogonal
 * condition. A network outage must never block the journey state machine; the
 * journey continues locally regardless of whether events can be uploaded.
 */
enum class JourneyInput {
    START,
    MOVING,
    STOP_CONFIRMED,
    RESTART,
    LONG_STOP,
    OVERNIGHT_CONFIRM,
    OVERNIGHT_DECLINE,
    PAUSE,
    RESUME,
    ARRIVED,
    COMPLETE,
    EXPIRE
}

object JourneyStateMachine {

    /**
     * Returns the next state, or null if the transition is not valid from the
     * current state (the caller should ignore invalid inputs rather than crash).
     */
    fun next(state: JourneyStatus, input: JourneyInput): JourneyStatus? {
        // EXPIRE and COMPLETE are terminal-ish and accepted from most states.
        if (input == JourneyInput.EXPIRE) return JourneyStatus.EXPIRED
        if (input == JourneyInput.COMPLETE &&
            state != JourneyStatus.COMPLETED && state != JourneyStatus.EXPIRED
        ) return JourneyStatus.COMPLETED

        return when (state) {
            JourneyStatus.READY -> when (input) {
                JourneyInput.MOVING -> JourneyStatus.DRIVING
                JourneyInput.STOP_CONFIRMED -> JourneyStatus.STOPPED
                JourneyInput.PAUSE -> JourneyStatus.PAUSED
                JourneyInput.ARRIVED -> JourneyStatus.ARRIVED
                else -> null
            }

            JourneyStatus.DRIVING -> when (input) {
                JourneyInput.STOP_CONFIRMED -> JourneyStatus.STOPPED
                JourneyInput.ARRIVED -> JourneyStatus.ARRIVED
                JourneyInput.PAUSE -> JourneyStatus.PAUSED
                JourneyInput.MOVING -> JourneyStatus.DRIVING
                else -> null
            }

            JourneyStatus.POSSIBLE_STOP -> when (input) {
                JourneyInput.STOP_CONFIRMED -> JourneyStatus.STOPPED
                JourneyInput.RESTART, JourneyInput.MOVING -> JourneyStatus.DRIVING
                JourneyInput.ARRIVED -> JourneyStatus.ARRIVED
                else -> null
            }

            JourneyStatus.STOPPED -> when (input) {
                JourneyInput.RESTART, JourneyInput.MOVING -> JourneyStatus.DRIVING
                JourneyInput.LONG_STOP -> JourneyStatus.LONG_STOP
                JourneyInput.ARRIVED -> JourneyStatus.ARRIVED
                JourneyInput.PAUSE -> JourneyStatus.PAUSED
                else -> null
            }

            JourneyStatus.LONG_STOP -> when (input) {
                JourneyInput.OVERNIGHT_CONFIRM -> JourneyStatus.OVERNIGHT
                JourneyInput.OVERNIGHT_DECLINE -> JourneyStatus.LONG_STOP
                JourneyInput.RESTART, JourneyInput.MOVING -> JourneyStatus.DRIVING
                JourneyInput.ARRIVED -> JourneyStatus.ARRIVED
                else -> null
            }

            JourneyStatus.OVERNIGHT -> when (input) {
                JourneyInput.RESTART, JourneyInput.MOVING -> JourneyStatus.DRIVING
                else -> null
            }

            JourneyStatus.PAUSED -> when (input) {
                JourneyInput.RESUME -> JourneyStatus.DRIVING
                JourneyInput.MOVING -> JourneyStatus.DRIVING
                else -> null
            }

            JourneyStatus.ARRIVED -> when (input) {
                // driver rolled again after a premature arrival detection
                JourneyInput.MOVING, JourneyInput.RESTART -> JourneyStatus.DRIVING
                else -> null
            }

            JourneyStatus.COMPLETED -> null
            JourneyStatus.EXPIRED -> null
        }
    }
}
