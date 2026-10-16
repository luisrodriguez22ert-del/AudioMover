package com.example.audiomover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY_LIST = "play_list"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val CHANNEL_ID = "playback"

        var currentTitle: String = ""
        var onTrackChanged: ((String) -> Unit)? = null
    }

    private var player: MediaPlayer? = null
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private val handler = Handler(Looper.getMainLooper())

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_LIST -> {
                uris = intent.getStringArrayListExtra("uris")?.map { Uri.parse(it) } ?: emptyList()
                titles = intent.getStringArrayListExtra("titles") ?: arrayListOf()
                index = intent.getIntExtra("index", 0)
                playCurrent()
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun playCurrent() {
        if (index < 0 || index >= uris.size) {
            stopAll()
            return
        }
        val title = titles.getOrElse(index) { "Reproduciendo" }
        currentTitle = title
        showForeground()
        onTrackChanged?.invoke(title)
        requestFocus()

        player?.release()
        player = null
        try {
            player = MediaPlayer().apply {
                setWakeMode(this@PlayerService, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(attrs)
                setDataSource(this@PlayerService, uris[index])
                setOnCompletionListener { handler.post { next() } }
                setOnErrorListener { _, _, _ ->
                    handler.post { next() }
                    true
                }
                prepare()
                start()
            }
            updateNotification()
        } catch (e: Exception) {
            // Si un archivo falla, salta al siguiente
            next()
        }
    }

    private fun next() {
        if (index + 1 < uris.size) {
            index++
            playCurrent()
        } else {
            stopAll()
        }
    }

    private fun prev() {
        val p = player
        if (p != null && p.currentPosition > 3000) {
            p.seekTo(0)
            return
        }
        if (index > 0) index--
        playCurrent()
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            requestFocus()
            p.start()
        }
        pausedByFocus = false
        updateNotification()
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    val p = player
                    if (p != null) {
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                if (p.isPlaying) p.pause()
                                pausedByFocus = false
                                updateNotification()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                if (p.isPlaying) {
                                    p.pause()
                                    pausedByFocus = true
                                    updateNotification()
                                }
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> p.setVolume(0.3f, 0.3f)
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                p.setVolume(1f, 1f)
                                if (pausedByFocus) {
                                    p.start()
                                    pausedByFocus = false
                                    updateNotification()
                                }
                            }
                        }
                    }
                }
                .build()
        }
        am.requestAudioFocus(focusRequest!!)
    }

    private fun serviceAction(label: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun buildNotification(): Notification {
        val playing = player?.isPlaying == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText("Audio Mover")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(serviceAction("Anterior", ACTION_PREV, 1))
            .addAction(serviceAction(if (playing) "Pausa" else "Play", ACTION_TOGGLE, 2))
            .addAction(serviceAction("Siguiente", ACTION_NEXT, 3))
            .addAction(serviceAction("Parar", ACTION_STOP, 4))
            .setOngoing(true)
            .build()
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reproducción", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun stopAll() {
        player?.release()
        player = null
        currentTitle = ""
        onTrackChanged?.invoke("")
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
