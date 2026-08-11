package com.trippulse.app

import com.trippulse.app.domain.JourneyInput
import com.trippulse.app.domain.JourneyStateMachine
import com.trippulse.app.domain.JourneyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyStateMachineTest {

    private fun next(s: JourneyStatus, i: JourneyInput) = JourneyStateMachine.next(s, i)

    @Test fun ready_to_driving_on_moving() {
        assertEquals(JourneyStatus.DRIVING, next(JourneyStatus.READY, JourneyInput.MOVING))
    }

    @Test fun driving_to_stopped_then_restart_to_driving() {
        assertEquals(JourneyStatus.STOPPED, next(JourneyStatus.DRIVING, JourneyInput.STOP_CONFIRMED))
        assertEquals(JourneyStatus.DRIVING, next(JourneyStatus.STOPPED, JourneyInput.RESTART))
    }

    @Test fun stopped_to_long_stop_then_overnight() {
        assertEquals(JourneyStatus.LONG_STOP, next(JourneyStatus.STOPPED, JourneyInput.LONG_STOP))
        assertEquals(JourneyStatus.OVERNIGHT, next(JourneyStatus.LONG_STOP, JourneyInput.OVERNIGHT_CONFIRM))
    }

    @Test fun overnight_declined_stays_long_stop() {
        assertEquals(JourneyStatus.LONG_STOP, next(JourneyStatus.LONG_STOP, JourneyInput.OVERNIGHT_DECLINE))
    }

    @Test fun overnight_resumes_to_driving() {
        assertEquals(JourneyStatus.DRIVING, next(JourneyStatus.OVERNIGHT, JourneyInput.MOVING))
    }

    @Test fun pause_and_resume() {
        assertEquals(JourneyStatus.PAUSED, next(JourneyStatus.DRIVING, JourneyInput.PAUSE))
        assertEquals(JourneyStatus.DRIVING, next(JourneyStatus.PAUSED, JourneyInput.RESUME))
    }

    @Test fun expire_is_accepted_from_any_active_state() {
        assertEquals(JourneyStatus.EXPIRED, next(JourneyStatus.DRIVING, JourneyInput.EXPIRE))
        assertEquals(JourneyStatus.EXPIRED, next(JourneyStatus.OVERNIGHT, JourneyInput.EXPIRE))
    }

    @Test fun complete_from_driving_but_not_from_terminal() {
        assertEquals(JourneyStatus.COMPLETED, next(JourneyStatus.DRIVING, JourneyInput.COMPLETE))
        assertNull(next(JourneyStatus.COMPLETED, JourneyInput.COMPLETE))
        assertNull(next(JourneyStatus.EXPIRED, JourneyInput.COMPLETE))
    }

    @Test fun invalid_inputs_return_null_not_crash() {
        assertNull(next(JourneyStatus.READY, JourneyInput.RESTART))
        assertNull(next(JourneyStatus.COMPLETED, JourneyInput.MOVING))
        assertNull(next(JourneyStatus.OVERNIGHT, JourneyInput.PAUSE))
    }

    @Test fun premature_arrival_can_roll_again() {
        assertEquals(JourneyStatus.ARRIVED, next(JourneyStatus.DRIVING, JourneyInput.ARRIVED))
        assertEquals(JourneyStatus.DRIVING, next(JourneyStatus.ARRIVED, JourneyInput.MOVING))
    }
}
