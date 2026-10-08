package com.clu.motion.input

/**
 * Decides whether injected touches may reach the current foreground app, from
 * TYPE_WINDOW_STATE_CHANGED events (package + class only; no window content is read).
 *
 * Blocked: this app's own settings screen (so tuning with a live preview is safe), the system
 * Settings and permission/installer screens (an injected drag must never toggle a setting or
 * grant a permission), and launchers (it would scroll the home screen). System UI panels are
 * ignored rather than blocking, because closing the shade does not reliably re-announce the game.
 *
 * Pure Kotlin so the policy is unit-testable.
 */
class ForegroundAppGate(
    private val ownPackage: String,
    /** Our own screens that must never receive injected touches (settings). */
    private val ownBlockedActivities: Set<String>,
    /** Our own screens built to receive them (the injection test pad). */
    private val ownInjectableActivities: Set<String>,
    private val homePackages: Set<String>,
) {
    @Volatile
    var currentPackage: String? = null
        private set

    /** Defaults to allowed until the first window event; the next app switch corrects it. */
    @Volatile
    var injectionAllowed = true
        private set

    /** @return the package name when a different, injectable app came to the foreground. */
    fun onWindowStateChanged(packageName: CharSequence?, className: CharSequence?): String? {
        val pkg = packageName?.toString() ?: return null
        val blocked = when {
            // Our own overlay windows also report our package; only our activities count.
            pkg == ownPackage -> when (className?.toString()) {
                in ownBlockedActivities -> true
                in ownInjectableActivities -> false
                else -> return null
            }
            pkg in IGNORED -> return null
            pkg in BLOCKED || pkg in homePackages -> true
            else -> false
        }
        injectionAllowed = !blocked
        val changed = pkg != currentPackage
        currentPackage = pkg
        return if (changed && !blocked) pkg else null
    }

    companion object {
        val IGNORED = setOf("com.android.systemui")
        val BLOCKED = setOf(
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
    }
}
