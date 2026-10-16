package com.example.audiomover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "play"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "playback"
    }

    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play(intent)
            ACTION_TOGGLE -> player?.let { if (it.isPlaying) it.pause() else it.start() }
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun play(intent: Intent) {
        val uri = intent.data ?: return
        val title = intent.getStringExtra("title") ?: "Reproduciendo"

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reproducción", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Audio Mover")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Detener", stopIntent).build())
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        player?.release()
        player = try {
            MediaPlayer().apply {
                setWakeMode(this@PlayerService, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@PlayerService, uri)
                setOnCompletionListener { stopAll() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            stopAll()
            null
        }
    }

    private fun stopAll() {
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
