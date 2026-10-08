package com.clu.motion.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppGateTest {
    private val own = "com.clu.motion"
    private val gate = ForegroundAppGate(own, setOf("com.clu.motion.ui.MainActivity"), setOf("com.android.launcher3"))

    @Test
    fun gamesAllowInjectionAndReportTheSwitchOnce() {
        assertEquals("com.example.game", gate.onWindowStateChanged("com.example.game", "com.unity3d.player.UnityPlayerActivity"))
        assertTrue(gate.injectionAllowed)
        assertNull(gate.onWindowStateChanged("com.example.game", "android.app.Dialog"))
    }

    @Test
    fun settingsLaunchersAndOurOwnScreenBlockInjection() {
        for ((pkg, cls) in listOf(
            "com.android.settings" to "com.android.settings.SubSettings",
            "com.google.android.permissioncontroller" to "x.GrantPermissionsActivity",
            "com.android.launcher3" to "com.android.launcher3.Launcher",
            own to "com.clu.motion.ui.MainActivity",
        )) {
            gate.onWindowStateChanged("com.example.game", "Game")
            assertNull(gate.onWindowStateChanged(pkg, cls))
            assertFalse(pkg, gate.injectionAllowed)
        }
    }

    @Test
    fun ourOverlaysAndSystemUiDoNotChangeTheDecision() {
        gate.onWindowStateChanged("com.example.game", "Game")
        assertNull(gate.onWindowStateChanged(own, "android.widget.LinearLayout"))
        assertNull(gate.onWindowStateChanged("com.android.systemui", "android.widget.FrameLayout"))
        assertTrue(gate.injectionAllowed)
        assertEquals("com.example.game", gate.currentPackage)
    }
}
