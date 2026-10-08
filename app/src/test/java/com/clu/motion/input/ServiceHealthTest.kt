package com.clu.motion.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceHealthTest {
    @Test
    fun boundIsRunningWhateverElseIsTrue() {
        assertEquals(ServiceHealth.RUNNING, ServiceHealth.evaluate(enabled = false, connected = true, everConnected = false, enabledSinceMs = null, nowMs = 0))
    }

    @Test
    fun enabledButUnboundIsStartingThenStuck() {
        assertEquals(ServiceHealth.STARTING, ServiceHealth.evaluate(true, false, true, enabledSinceMs = 1_000, nowMs = 5_000))
        assertEquals(ServiceHealth.STUCK, ServiceHealth.evaluate(true, false, true, enabledSinceMs = 1_000, nowMs = 11_000))
    }

    @Test
    fun offAfterHavingWorkedIsTurnedOffNotNeverSetUp() {
        assertEquals(ServiceHealth.TURNED_OFF, ServiceHealth.evaluate(false, false, everConnected = true, enabledSinceMs = null, nowMs = 0))
        assertEquals(ServiceHealth.OFF, ServiceHealth.evaluate(false, false, everConnected = false, enabledSinceMs = null, nowMs = 0))
    }
}
