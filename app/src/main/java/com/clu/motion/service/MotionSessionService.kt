package com.clu.motion.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clu.motion.CluApp
import com.clu.motion.R
import com.clu.motion.engine.MotionEngine
import com.clu.motion.engine.SessionState
import com.clu.motion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground companion for an active session.
 *
 * The accessibility binding already keeps the process at near-foreground priority while the
 * screen is on; this service adds (1) resistance to OEM task killers that ignore that,
 * (2) a "microphone" FGS type so the optional sound trigger keeps receiving audio in the
 * background, and (3) persistent notification controls — Pause/Resume, Recenter, Stop —
 * reachable by Switch Access and Voice Access from the notification shade.
 *
 * START_NOT_STICKY: if the system kills the session it must never silently restart injecting.
 */
class MotionSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MotionEngine

    override fun onBind(intent: Intent?): IBinder? = null

    private var observing = false

    override fun onCreate() {
        super.onCreate()
        engine = CluApp.engine(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must come first: stopping a service started with
        // startForegroundService() before it is foreground crashes the app.
        if (!goForeground()) return START_NOT_STICKY
        if (!observing) {
            observing = true
            scope.launch {
                combine(engine.session, engine.activeProfile) { state, profile -> state to profile.name }
                    .distinctUntilChanged()
                    .collect { (state, profileName) ->
                        if (state == SessionState.Stopped) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(state, profileName))
                        }
                    }
            }
        }
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> engine.togglePause()
            ACTION_RECALIBRATE -> engine.recalibrate()
            ACTION_STOP -> engine.stopSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val n = notification(engine.session.value, engine.activeProfile.value.name)
        // Background microphone access needs a "microphone" FGS from Android 11 (API 30).
        val wantMic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            engine.activeProfile.value.sound.enabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val micType = if (wantMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            startForegroundCompat(n, baseType or micType)
            true
        } catch (e: SecurityException) {
            // A "microphone" FGS can only start while the app is visible (Android 11+ while-in-use).
            Log.w(TAG, "Microphone FGS type refused; continuing without it", e)
            tryStart(n, baseType)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground service not allowed now", e)
            stopSelf()
            false
        }
    }

    private fun tryStart(n: Notification, type: Int): Boolean = try {
        startForegroundCompat(n, type)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Foreground service start failed", e)
        stopSelf()
        false
    }

    private fun startForegroundCompat(n: Notification, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, type)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notification(state: SessionState, profileName: String): Notification {
        val title = getString(
            when (state) {
                SessionState.Active -> R.string.notif_active
                is SessionState.Paused -> R.string.notif_paused
                SessionState.Stopped -> R.string.notif_stopped
            },
        )
        val toggleLabel = if (state is SessionState.Paused) R.string.hud_resume else R.string.hud_pause
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_motion)
            .setContentTitle(title)
            .setContentText(profileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(activityIntent())
            .addAction(0, getString(toggleLabel), serviceIntent(ACTION_TOGGLE_PAUSE, 1))
            .addAction(0, getString(R.string.hud_recenter), serviceIntent(ACTION_RECALIBRATE, 2))
            .addAction(0, getString(R.string.hud_stop), serviceIntent(ACTION_STOP, 3))
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, MotionSessionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun activityIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MotionSessionService"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE_PAUSE = "com.clu.motion.action.TOGGLE_PAUSE"
        private const val ACTION_RECALIBRATE = "com.clu.motion.action.RECALIBRATE"
        private const val ACTION_STOP = "com.clu.motion.action.STOP"

        /** @return false if Android refused a foreground-service start from the current state. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, MotionSessionService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
            Log.w(TAG, "Could not start foreground service", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MotionSessionService::class.java))
        }
    }
}
