package dev.pocketdesk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import dev.pocketdesk.MainActivity
import dev.pocketdesk.R
import dev.pocketdesk.StopSessionActivity
import dev.pocketdesk.net.Stats
import dev.pocketdesk.session.SessionHub

/**
 * Keeps the session alive when the app is no longer visible.
 *
 * The notification is deliberately the only stop control while the app is in
 * the background, and it opens a confirmation dialog rather than dropping the
 * connection on a single tap.
 */
class RemoteService : Service(), SessionHub.Listener {

    private var lastText: String = ""
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        SessionHub.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(textForState())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        lastText = textForState()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        SessionHub.removeListener(this)
        super.onDestroy()
    }

    // ── session updates ───────────────────────────────────────────────

    override fun onState(state: SessionHub.State, detail: String?) {
        if (!foregroundStarted) return

        when (state) {
            SessionHub.State.CONNECTING ->
                update(getString(R.string.notification_connecting, SessionHub.target))

            SessionHub.State.CONNECTED ->
                update(getString(R.string.notification_connected, SessionHub.target))

            SessionHub.State.RECONNECTING ->
                update(getString(R.string.notification_reconnecting, SessionHub.target))

            SessionHub.State.FAILED -> {
                update(getString(R.string.notification_disconnected))
                // Nothing is left to keep alive; the app will offer a reconnect.
                stopSelf()
            }

            SessionHub.State.IDLE ->
                update(getString(R.string.notification_stopped))
        }
    }

    override fun onStats(stats: Stats) {
        if (!foregroundStarted) return
        if (SessionHub.state == SessionHub.State.CONNECTED) {
            update(getString(R.string.notification_streaming, SessionHub.target, stats.fps.toInt()))
        }
    }

    private fun textForState(): String = when (SessionHub.state) {
        SessionHub.State.CONNECTED -> getString(R.string.notification_connected, SessionHub.target)
        SessionHub.State.RECONNECTING -> getString(R.string.notification_reconnecting, SessionHub.target)
        SessionHub.State.FAILED -> getString(R.string.notification_disconnected)
        SessionHub.State.IDLE, SessionHub.State.CONNECTING ->
            getString(R.string.notification_connecting, SessionHub.target)
    }

    private fun update(text: String) {
        if (text == lastText) return
        lastText = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // The Stop action asks first; see StopSessionActivity.
        val stop = PendingIntent.getActivity(
            this,
            1,
            Intent(this, StopSessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pocketdesk)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_pocketdesk),
                    getString(R.string.stop_confirm),
                    stop,
                ).build(),
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }
    }
}
