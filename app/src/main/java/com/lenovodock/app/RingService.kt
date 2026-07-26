package com.lenovodock.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Plays the bundled alarm sound and shows a dismiss-able notification until stopped. */
class RingService : Service() {
    private var player: MediaPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Alarm"
        startForeground(NOTIF_ID, buildNotification(label))
        startSound()
        return START_STICKY
    }

    private fun startSound() {
        stopSound()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            try {
                val afd = resources.openRawResourceFd(R.raw.alarm_sound)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close(); prepare(); start()
            } catch (e: Exception) { /* missing res/raw/alarm_sound: fail silently */ }
        }
    }

    private fun stopSound() {
        player?.apply { if (isPlaying) stop(); release() }
        player = null
    }

    private fun buildNotification(label: String): Notification {
        val channelId = "lenovodock_alarms"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Alarms & Timers", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val dismissPending = PendingIntent.getBroadcast(
            this, 0, Intent(this, DismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(label).setContentText("Tap Dismiss to stop")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Dismiss", dismissPending)
            .build()
    }

    override fun onDestroy() { stopSound(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 4201
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, RingService::class.java).putExtra(EXTRA_LABEL, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, RingService::class.java)) }
    }
}