package com.clu.motion.input

/** What the setup screen should say about the accessibility service. */
enum class ServiceHealth {
    /** Bound and injecting. */
    RUNNING,

    /** Enabled in Settings, waiting for the system to bind it. */
    STARTING,

    /**
     * Enabled in Settings but not bound for a while. Android leaves a crashed service in the
     * enabled list while nothing runs (seen on HyperOS 3 / Android 16): toggling it off and on
     * usually recovers it.
     */
    STUCK,

    /**
     * Was working before and is now off. A force stop (App info, phone-cleaner tools, Xiaomi
     * Ultra battery saver) removes the service from the enabled list on Android 16.
     */
    TURNED_OFF,

    /** Never enabled yet. */
    OFF,
    ;

    companion object {
        const val STUCK_AFTER_MS = 10_000L

        /** Pure policy so it can be unit-tested; [enabledSinceMs] = when we first saw "enabled but not bound". */
        fun evaluate(enabled: Boolean, connected: Boolean, everConnected: Boolean, enabledSinceMs: Long?, nowMs: Long): ServiceHealth =
            when {
                connected -> RUNNING
                enabled && enabledSinceMs != null && nowMs - enabledSinceMs >= STUCK_AFTER_MS -> STUCK
                enabled -> STARTING
                everConnected -> TURNED_OFF
                else -> OFF
            }
    }
}
