package com.example.audiomover

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

private const val REQ_MOVE = 100
private const val REQ_DELETE = 101
private const val LIB_PATH = "Music/AudioMover/"
private const val MENU_MOVE_ALL = 10
private const val MENU_AUTO = 11
private val NAVY = Color.parseColor("#14161F")
private val GRAY = Color.parseColor("#9CA3AF")
private val ACTIVE = Color.parseColor("#1E88C8")
private val INACTIVE = Color.parseColor("#9A9A94")
private val PLAYER_BG = Color.parseColor("#ECEBE6")

data class Audio(
    val id: Long,
    val uri: Uri,
    val name: String,
    val title: String,
    val artist: String,
    val durationMs: Long
)

class VH(v: View) : RecyclerView.ViewHolder(v) {
    val name: TextView = v.findViewById(R.id.name)
    val sub: TextView = v.findViewById(R.id.sub)
    val num: TextView = v.findViewById(R.id.num)
    val eq: ImageView = v.findViewById(R.id.eq)
    val menu: ImageButton = v.findViewById(R.id.menu)
}

class MainActivity : Activity() {

    private val downloads = mutableListOf<Audio>()
    private val library = mutableListOf<Audio>()
    private var shown: MutableList<Audio> = downloads
    private var tab = 1 // 0 = Descargas, 1 = Mi música, 2 = Buscar
    private val navTabs = intArrayOf(0, 2, 1)
    private var query = ""
    private var autoMove = true
    private var pendingUris: List<Uri> = emptyList()
    private var lastPlaying = false
    private var dragging = false

    private lateinit var prefs: SharedPreferences
    private lateinit var audio: AudioManager
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AudioAdapter
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var headerBox: View
    private lateinit var searchBox: View
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvSub: TextView
    private lateinit var navItems: List<LinearLayout>
    private lateinit var miniPlayer: View
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniToggle: ImageButton
    private lateinit var screenPlayer: View
    private lateinit var pTitle: TextView
    private lateinit var pArtist: TextView
    private lateinit var disc: DiscView
    private lateinit var seek: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnToggle: ImageButton

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = PlayerService.playerRef
            var playing = false
            if (p == null) {
                if (!dragging) {
                    seek.progress = 0
                    tvPos.text = "0:00"
                    tvDur.text = "0:00"
                }
            } else {
                try {
                    playing = p.isPlaying
                    if (!dragging) {
                        val dur = p.duration
                        val cur = p.currentPosition
                        if (dur > 0) {
                            seek.max = dur
                            seek.progress = cur
                            tvPos.text = fmt(cur)
                            tvDur.text = fmt(dur)
                        }
                    }
                } catch (e: Exception) {
                }
            }
            if (playing != lastPlaying) {
                lastPlaying = playing
                val res = if (playing) R.drawable.ic_pause else R.drawable.ic_play
                btnToggle.setImageResource(res)
                miniToggle.setImageResource(res)
            }
            if (screenPlayer.visibility == View.VISIBLE) disc.setVolumeExternal(currentVolume())
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        audio = getSystemService(AudioManager::class.java)
        autoMove = prefs.getBoolean("auto", true)
        PlayerService.shuffle = prefs.getBoolean("shuffle", false)
        PlayerService.repeatMode = prefs.getInt("repeat", 0)

        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        headerBox = findViewById(R.id.headerBox)
        searchBox = findViewById(R.id.searchBox)
        tvTitle = findViewById(R.id.tvTitle)
        tvMeta = findViewById(R.id.tvMeta)
        tvSub = findViewById(R.id.tvSub)
        navItems = listOf(
            findViewById<LinearLayout>(R.id.navDownloads),
            findViewById<LinearLayout>(R.id.navSearch),
            findViewById<LinearLayout>(R.id.navLibrary)
        )
        miniPlayer = findViewById(R.id.miniPlayer)
        miniArt = findViewById(R.id.miniArt)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniToggle = findViewById(R.id.miniToggle)
        screenPlayer = findViewById(R.id.screenPlayer)
        pTitle = findViewById(R.id.pTitle)
        pArtist = findViewById(R.id.pArtist)
        disc = findViewById(R.id.disc)
        seek = findViewById(R.id.seek)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnToggle = findViewById(R.id.btnToggle)

        adapter = AudioAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Arrastrar para reordenar (mantener pulsado) solo en "Mi música"
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = tab == 1

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (tab != 1) return false
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                shown.add(to, shown.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (tab == 1) {
                    saveOrder()
                    rv.post { adapter.notifyDataSetChanged() }
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        // Navegación inferior
        for (i in navItems.indices) {
            navItems[i].setOnClickListener { setTab(navTabs[i]) }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
                updateHeader()
            }
        })

        findViewById<View>(R.id.btnPlayAll).setOnClickListener {
            if (shown.isEmpty()) toast("No hay canciones aquí") else playFrom(0)
        }
        findViewById<View>(R.id.btnShuffleAll).setOnClickListener {
            if (shown.isEmpty()) {
                toast("No hay canciones aquí")
            } else {
                PlayerService.shuffle = true
                prefs.edit().putBoolean("shuffle", true).apply()
                updateModeButtons()
                playFrom((0 until shown.size).random())
            }
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showTopMenu(it) }

        // Mini reproductor y pantalla del reproductor
        miniPlayer.setOnClickListener { openPlayer() }
        miniToggle.setOnClickListener { sendToService(PlayerService.ACTION_TOGGLE) }
        findViewById<View>(R.id.btnBack).setOnClickListener { closePlayer() }
        findViewById<ImageButton>(R.id.btnStop).setOnClickListener {
            sendToService(PlayerService.ACTION_STOP)
            closePlayer()
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { sendToService(PlayerService.ACTION_PREV) }
        btnToggle.setOnClickListener { sendToService(PlayerService.ACTION_TOGGLE) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { sendToService(PlayerService.ACTION_NEXT) }

        btnShuffle.setOnClickListener {
            PlayerService.shuffle = !PlayerService.shuffle
            prefs.edit().putBoolean("shuffle", PlayerService.shuffle).apply()
            updateModeButtons()
            sendToService(PlayerService.ACTION_MODE)
            toast(if (PlayerService.shuffle) "Aleatorio activado" else "Aleatorio desactivado")
        }
        btnRepeat.setOnClickListener {
            PlayerService.repeatMode = (PlayerService.repeatMode + 1) % 3
            prefs.edit().putInt("repeat", PlayerService.repeatMode).apply()
            updateModeButtons()
            toast(
                when (PlayerService.repeatMode) {
                    1 -> "Repetir toda la lista"
                    2 -> "Repetir una canción"
                    else -> "Repetir desactivado"
                }
            )
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = fmt(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                startService(
                    Intent(this@MainActivity, PlayerService::class.java)
                        .setAction(PlayerService.ACTION_SEEK)
                        .putExtra("pos", sb.progress)
                )
            }
        })

        // Volumen con el arco del disco
        disc.onVolumeChange = { v ->
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
        }

        updateModeButtons()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        PlayerService.onTrackChanged = { t -> runOnUiThread { showNow(t) } }
        ticker.post(tick)
        if (hasAudioPermission()) {
            loadAudios()
        } else {
            status.text = "Falta el permiso de audio. Actívalo en Ajustes > Apps > MOODI Song."
        }
    }

    override fun onPause() {
        PlayerService.onTrackChanged = null
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screenPlayer.visibility == View.VISIBLE) closePlayer() else super.onBackPressed()
    }

    // ---------- Menú superior ----------
    private fun showTopMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, MENU_MOVE_ALL, 0, "Pasar todo a Mi música")
        val auto = pm.menu.add(0, MENU_AUTO, 1, "Pasar automáticamente")
        auto.isCheckable = true
        auto.isChecked = autoMove
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MOVE_ALL -> {
                    if (downloads.isEmpty()) toast("No hay audios en Descargas")
                    else requestMove(downloads.toList())
                }
                MENU_AUTO -> {
                    autoMove = !autoMove
                    prefs.edit().putBoolean("auto", autoMove).apply()
                    toast(if (autoMove) "Paso automático activado" else "Paso automático desactivado")
                }
            }
            true
        }
        pm.show()
    }

    // ---------- Permisos ----------
    private fun askPermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    // ---------- Cargar listas ----------
    private fun loadAudios() {
        val dl = mutableListOf<Audio>()
        val lib = mutableListOf<Audio>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        try {
            contentResolver.query(
                collection, projection, null, null,
                MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "?"
                    val path = c.getString(2) ?: ""
                    val title = c.getString(3)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
                    val artist = c.getString(4) ?: ""
                    val a = Audio(
                        id, ContentUris.withAppendedId(collection, id),
                        name, title, artist, c.getLong(5)
                    )
                    when {
                        path.startsWith(LIB_PATH) -> lib.add(a)
                        path.startsWith("Download") -> dl.add(a)
                    }
                }
            }
        } catch (e: SecurityException) {
            status.text = "Falta el permiso de audio."
            return
        }

        // Orden guardado de "Mi música"; los nuevos se agregan al final
        val order = savedOrder()
        val known = order.toSet()
        val byId = lib.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] } + lib.filter { it.id !in known }

        downloads.clear()
        downloads.addAll(dl)
        library.clear()
        library.addAll(ordered)
        saveOrder()
        refresh()
        maybeAutoMove()
    }

    // Ofrece pasar a Mi música las descargas nuevas (una sola confirmación del sistema)
    private fun maybeAutoMove() {
        val raw = prefs.getString("offered", null)
        if (raw == null) {
            // Primera vez: lo que ya existe no se toca automáticamente
            prefs.edit().putString("offered", downloads.joinToString(",") { it.id.toString() }).apply()
            return
        }
        if (!autoMove) return
        val offered = raw.split(",").mapNotNull { it.toLongOrNull() }.toMutableSet()
        val fresh = downloads.filter { it.id !in offered }
        if (fresh.isEmpty()) return
        offered.addAll(fresh.map { it.id })
        prefs.edit().putString("offered", offered.joinToString(",")).apply()
        requestMove(fresh)
    }

    private fun savedOrder(): List<Long> =
        (prefs.getString("order", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }

    private fun saveOrder() {
        prefs.edit().putString("order", library.joinToString(",") { it.id.toString() }).apply()
    }

    // ---------- Pestañas, búsqueda y cabecera ----------
    private fun setTab(t: Int) {
        tab = t
        refresh()
        if (t == 2) {
            search.requestFocus()
            search.post {
                getSystemService(InputMethodManager::class.java)
                    .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            search.clearFocus()
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    private fun applyFilter() {
        shown = when (tab) {
            0 -> downloads
            1 -> library
            else -> {
                val q = query.trim()
                if (q.isEmpty()) mutableListOf()
                else (library + downloads).filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.name.contains(q, ignoreCase = true) ||
                        it.artist.contains(q, ignoreCase = true)
                }.toMutableList()
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateNav() {
        for (i in navItems.indices) {
            val sel = navTabs[i] == tab
            val item = navItems[i]
            (item.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(if (sel) NAVY else GRAY)
            val label = item.getChildAt(1) as TextView
            label.setTextColor(if (sel) NAVY else GRAY)
            label.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun songs(n: Int) = if (n == 1) "1 canción" else "$n canciones"

    private fun updateHeader() {
        val isSearch = tab == 2
        headerBox.visibility = if (isSearch) View.GONE else View.VISIBLE
        searchBox.visibility = if (isSearch) View.VISIBLE else View.GONE
        if (!isSearch) {
            tvTitle.text = if (tab == 0) "Descargas" else "Mi música"
            tvMeta.text = (if (tab == 0) "Carpeta · " else "Lista · ") + songs(shown.size)
            tvSub.text = "MOODI Song"
        }
        status.text = when {
            isSearch && query.trim().isEmpty() -> "Escribe para buscar en toda tu música"
            isSearch -> "${shown.size} resultados"
            tab == 1 -> "Mantén pulsado y arrastra para cambiar el orden"
            else -> "Con los tres puntos pasa canciones a Mi música o elimínalas"
        }
    }

    private fun refresh() {
        updateNav()
        applyFilter()
        showNow(PlayerService.currentTitle)
    }

    private fun showNow(title: String) {
        val active = title.isNotEmpty()
        miniPlayer.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            miniTitle.text = PlayerService.currentDisplayTitle
            miniArtist.text = PlayerService.currentArtist
            miniArt.setImageBitmap(PlayerService.currentArt)
            pTitle.text = PlayerService.currentDisplayTitle
            pArtist.text = PlayerService.currentArtist
            disc.cover = PlayerService.currentArt
        } else if (screenPlayer.visibility == View.VISIBLE) {
            closePlayer()
        }
        updateHeader()
        adapter.notifyDataSetChanged()
    }

    private fun updateModeButtons() {
        btnShuffle.imageTintList = ColorStateList.valueOf(if (PlayerService.shuffle) ACTIVE else INACTIVE)
        btnRepeat.setImageResource(
            if (PlayerService.repeatMode == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        btnRepeat.imageTintList =
            ColorStateList.valueOf(if (PlayerService.repeatMode != 0) ACTIVE else INACTIVE)
    }

    // ---------- Pantalla del reproductor ----------
    private fun openPlayer() {
        if (PlayerService.currentTitle.isEmpty()) return
        screenPlayer.visibility = View.VISIBLE
        window.statusBarColor = PLAYER_BG
        window.navigationBarColor = PLAYER_BG
        disc.setVolumeExternal(currentVolume())
        showNow(PlayerService.currentTitle)
    }

    private fun closePlayer() {
        screenPlayer.visibility = View.GONE
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else 0f
    }

    // ---------- Reproducir ----------
    private fun playFrom(pos: Int) {
        if (pos < 0 || pos >= shown.size) return
        val i = Intent(this, PlayerService::class.java)
            .setAction(PlayerService.ACTION_PLAY_LIST)
            .putStringArrayListExtra("uris", ArrayList(shown.map { it.uri.toString() }))
            .putStringArrayListExtra("titles", ArrayList(shown.map { it.name }))
            .putExtra("index", pos)
        startForegroundService(i)
    }

    private fun sendToService(action: String) {
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    // ---------- Mover y eliminar ----------
    private fun showMenu(anchor: View, a: Audio) {
        val inLib = library.any { it.id == a.id }
        val pm = PopupMenu(this, anchor)
        if (!inLib) pm.menu.add(0, 1, 0, "Pasar a Mi música")
        pm.menu.add(0, 2, 1, "Eliminar")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> requestMove(listOf(a))
                2 -> requestDelete(listOf(a))
            }
            true
        }
        pm.show()
    }

    private fun requestMove(sel: List<Audio>) {
        pendingUris = sel.map { it.uri }
        val pi = MediaStore.createWriteRequest(contentResolver, pendingUris)
        startIntentSenderForResult(pi.intentSender, REQ_MOVE, null, 0, 0, 0)
    }

    private fun requestDelete(sel: List<Audio>) {
        val pi = MediaStore.createDeleteRequest(contentResolver, sel.map { it.uri })
        startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_MOVE -> {
                var ok = 0
                for (u in pendingUris) {
                    try {
                        val v = ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, LIB_PATH)
                        }
                        if (contentResolver.update(u, v, null, null) > 0) ok++
                    } catch (e: Exception) {
                    }
                }
                toast("Movidos $ok de ${pendingUris.size} a Mi música")
                loadAudios()
            }
            REQ_DELETE -> {
                toast("Eliminado")
                loadAudios()
            }
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun subtitle(a: Audio): String {
        val who = if (a.artist.isBlank() || a.artist == MediaStore.UNKNOWN_STRING)
            "Artista desconocido" else a.artist
        return who + " · " + fmt(a.durationMs.toInt())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Adaptador ----------
    private inner class AudioAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_audio, parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = shown[position]
            val playing = a.name == PlayerService.currentTitle
            h.name.text = a.title
            h.sub.text = subtitle(a)
            h.num.text = "%02d".format(position + 1)
            h.num.visibility = if (playing) View.INVISIBLE else View.VISIBLE
            h.eq.visibility = if (playing) View.VISIBLE else View.GONE
            h.itemView.setBackgroundResource(if (playing) R.drawable.bg_playing else 0)
            h.menu.setOnClickListener { showMenu(it, a) }
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) playFrom(pos)
            }
        }
    }
}
