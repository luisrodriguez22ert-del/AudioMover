package com.example.audiomover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY_LIST = "play_list"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val ACTION_SEEK = "seek"
        const val ACTION_MODE = "mode"
        const val CHANNEL_ID = "playback_v2"

        var currentTitle: String = ""
        var onTrackChanged: ((String) -> Unit)? = null
        var playerRef: MediaPlayer? = null
        var currentDisplayTitle: String = ""
        var currentArtist: String = ""
        var currentArt: Bitmap? = null

        var shuffle = false
        var repeatMode = 0 // 0 = no repetir, 1 = toda la lista, 2 = una canción
    }

    private var player: MediaPlayer? = null
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var order: List<Int> = emptyList()
    private var pos = 0
    private var failures = 0

    private var displayTitle = ""
    private var artist = "MOODI Song"
    private var art: Bitmap? = null
    private var durationMs = 0L

    private lateinit var session: MediaSession
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private val handler = Handler(Looper.getMainLooper())

    private val defaultArt: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.ic_launcher)
    }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // Pausa cuando se desconectan audífonos o Bluetooth
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseIt()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "AudioMover")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pauseIt()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = prev()
            override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            override fun onStop() = stopAll()
        })
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_LIST -> {
                uris = intent.getStringArrayListExtra("uris")?.map { Uri.parse(it) } ?: emptyList()
                titles = intent.getStringArrayListExtra("titles") ?: arrayListOf()
                if (uris.isEmpty()) {
                    showForeground()
                    stopAll()
                } else {
                    buildOrder(intent.getIntExtra("index", 0).coerceIn(0, uris.size - 1))
                    failures = 0
                    playCurrent()
                }
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_SEEK -> seekTo(intent.getIntExtra("pos", 0))
            ACTION_MODE -> if (order.isNotEmpty() && pos in order.indices) buildOrder(order[pos])
            ACTION_STOP -> stopAll()
        }
        if (player == null) stopSelf()
        return START_NOT_STICKY
    }

    // ---------- Orden de reproducción (normal o aleatorio) ----------
    private fun buildOrder(startIndex: Int) {
        val n = uris.size
        if (shuffle) {
            val rest = (0 until n).filter { it != startIndex }.shuffled()
            order = listOf(startIndex) + rest
            pos = 0
        } else {
            order = (0 until n).toList()
            pos = startIndex
        }
    }

    // ---------- Reproducción ----------
    private fun setPlayer(p: MediaPlayer?) {
        player = p
        playerRef = p
    }

    private fun releasePlayer() {
        val p = player
        setPlayer(null)
        p?.release()
    }

    private fun playCurrent() {
        if (order.isEmpty() || pos !in order.indices) {
            stopAll()
            return
        }
        val i = order[pos]
        val file = titles.getOrElse(i) { "Reproduciendo" }
        currentTitle = file
        displayTitle = file.substringBeforeLast('.')
        artist = "MOODI Song"
        art = null
        durationMs = 0L
        showForeground()
        loadMeta(uris[i])
        currentDisplayTitle = displayTitle
        currentArtist = artist
        currentArt = art ?: defaultArt
        onTrackChanged?.invoke(file)
        requestFocus()
        releasePlayer()

        val p = MediaPlayer()
        try {
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            p.setAudioAttributes(attrs)
            p.setDataSource(this, uris[i])
            p.setOnCompletionListener {
                handler.post {
                    if (player === p) {
                        if (repeatMode == 2) playCurrent() else next()
                    }
                }
            }
            p.setOnErrorListener { _, _, _ ->
                handler.post { if (player === p) skipBroken() }
                true
            }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            p.release()
            skipBroken()
            return
        }
        failures = 0
        setPlayer(p)
        durationMs = p.duration.toLong()
        session.isActive = true
        updateMetadata()
        refreshAll()
    }

    private fun skipBroken() {
        failures++
        if (failures >= order.size) {
            failures = 0
            stopAll()
        } else {
            next()
        }
    }

    private fun next() {
        if (order.isEmpty()) {
            stopAll()
            return
        }
        if (pos + 1 < order.size) {
            pos++
            playCurrent()
        } else if (repeatMode == 1) {
            pos = 0
            playCurrent()
        } else {
            stopAll()
        }
    }

    private fun prev() {
        val p = player
        if (p != null && p.currentPosition > 3000) {
            p.seekTo(0)
            refreshAll()
            return
        }
        if (pos > 0) pos-- else if (repeatMode == 1 && order.isNotEmpty()) pos = order.size - 1
        playCurrent()
    }

    private fun toggle() {
        val p = player ?: return
        if (p.isPlaying) pauseIt() else resume()
    }

    private fun pauseIt() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        pausedByFocus = false
        refreshAll()
    }

    private fun resume() {
        val p = player ?: return
        requestFocus()
        p.start()
        refreshAll()
    }

    private fun seekTo(ms: Int) {
        if (player == null) return
        player?.seekTo(ms)
        refreshAll()
    }

    // ---------- Metadatos (título, artista, carátula) ----------
    private fun loadMeta(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { displayTitle = it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            r.embeddedPicture?.let {
                art = BitmapFactory.decodeByteArray(it, 0, it.size)?.let { b -> scaled(b) }
            }
        } catch (e: Exception) {
            // sin etiquetas: se usa el nombre del archivo
        } finally {
            r.release()
        }
    }

    private fun scaled(b: Bitmap): Bitmap {
        val max = 512
        val s = maxOf(b.width, b.height)
        if (s <= max) return b
        val f = max.toFloat() / s
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun updateMetadata() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art ?: defaultArt)
                .build()
        )
    }

    private fun updateSession() {
        val p = player
        val state = when {
            p == null -> PlaybackState.STATE_STOPPED
            p.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val position = p?.currentPosition?.toLong() ?: 0L
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, position, 1f)
                .build()
        )
    }

    // ---------- Audio focus ----------
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
                                refreshAll()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                if (p.isPlaying) {
                                    p.pause()
                                    pausedByFocus = true
                                    refreshAll()
                                }
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> p.setVolume(0.3f, 0.3f)
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                p.setVolume(1f, 1f)
                                if (pausedByFocus) {
                                    p.start()
                                    pausedByFocus = false
                                    refreshAll()
                                }
                            }
                        }
                    }
                }
                .build()
        }
        am.requestAudioFocus(focusRequest!!)
    }

    // ---------- Notificación estilo reproductor ----------
    private fun iconAction(res: Int, label: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, res), label, pi).build()
    }

    private fun buildNotification(): Notification {
        val playing = player?.isPlaying == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(displayTitle)
            .setContentText(artist)
            .setLargeIcon(art ?: defaultArt)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Color.BLACK)
            .setColorized(true)
            .addAction(iconAction(R.drawable.ic_prev, "Anterior", ACTION_PREV, 1))
            .addAction(
                if (playing) iconAction(R.drawable.ic_pause, "Pausa", ACTION_TOGGLE, 2)
                else iconAction(R.drawable.ic_play, "Reproducir", ACTION_TOGGLE, 2)
            )
            .addAction(iconAction(R.drawable.ic_next, "Siguiente", ACTION_NEXT, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Reproducción", NotificationManager.IMPORTANCE_LOW)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(channel)
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun refreshAll() {
        updateSession()
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun stopAll() {
        releasePlayer()
        currentTitle = ""
        currentDisplayTitle = ""
        currentArtist = ""
        currentArt = null
        failures = 0
        onTrackChanged?.invoke("")
        session.isActive = false
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
        }
        releasePlayer()
        session.release()
        super.onDestroy()
    }
}
