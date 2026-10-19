package com.myreader.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myreader.app.MyReaderApp
import com.myreader.app.R
import com.myreader.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while narration runs and
 * exposes pause/prev/next controls on the notification shade.
 */
class PlayerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player get() = (application as MyReaderApp).player

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            player.state.collectLatest { st ->
                if (st.bookId == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateNotification(st)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.toggle()
            ACTION_NEXT -> player.nextSentence()
            ACTION_PREV -> player.previousSentence()
            ACTION_STOP -> {
                player.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Must enter the foreground promptly after startForegroundService().
        if (player.state.value.bookId != null) {
            promote()
            updateNotification(player.state.value)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Keep speaking if the user simply killed the service task; only stop
        // when playback itself has ended.
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote() {
        val notification = buildNotification(
            title = player.state.value.title.ifBlank { getString(R.string.app_name) },
            text = "Reading…",
            playing = player.state.value.isPlaying,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(st: com.myreader.app.tts.Player.PlayerState) {
        val chapter = st.chapterTitles.getOrNull(st.chapterIndex).orEmpty()
        val text = buildString {
            if (chapter.isNotEmpty()) append(chapter)
            append(" • sentence ${st.sentenceIndex + 1} of ${st.sentenceCount}")
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(st.title.ifBlank { getString(R.string.app_name) }, text, st.isPlaying),
        )
    }

    private fun buildNotification(title: String, text: String, playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val actionIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_read)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_rew, "Previous", pendingService(ACTION_PREV, 1),
            )
            .addAction(
                actionIcon,
                if (playing) "Pause" else "Play",
                pendingService(ACTION_PLAY_PAUSE, 2),
            )
            .addAction(
                android.R.drawable.ic_media_ff, "Next", pendingService(ACTION_NEXT, 3),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                pendingService(ACTION_STOP, 4),
            )
            .build()
    }

    private fun pendingService(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reading",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing audiobook playback" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "reading"
        private const val NOTIFICATION_ID = 10
        const val ACTION_PLAY_PAUSE = "com.myreader.app.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.myreader.app.action.NEXT"
        const val ACTION_PREV = "com.myreader.app.action.PREV"
        const val ACTION_STOP = "com.myreader.app.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlayerService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlayerService::class.java).setAction(ACTION_STOP))
        }
    }
}
