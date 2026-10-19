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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private const val REQ_MOVE = 100
private const val REQ_DELETE = 101
private const val LIB_PATH = "Music/AudioMover/"
private const val MENU_MOVE_ALL = 10
private const val MENU_AUTO = 11
private val ACTIVE = Color.parseColor("#1E40AF")
private val INACTIVE = Color.parseColor("#555555")

data class Audio(val id: Long, val uri: Uri, val name: String, val folder: String)

class VH(v: View) : RecyclerView.ViewHolder(v) {
    val name: TextView = v.findViewById(R.id.name)
    val folder: TextView = v.findViewById(R.id.folder)
    val menu: ImageButton = v.findViewById(R.id.menu)
}

class MainActivity : Activity() {

    private val downloads = mutableListOf<Audio>()
    private val library = mutableListOf<Audio>()
    private var shown: MutableList<Audio> = downloads
    private var inLibrary = false
    private var query = ""
    private var autoMove = true
    private var pendingUris: List<Uri> = emptyList()
    private var lastPlaying = false

    private lateinit var prefs: SharedPreferences
    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var btnDownloads: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnToggle: ImageButton
    private lateinit var adapter: AudioAdapter
    private lateinit var seek: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private var dragging = false

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
                        val d = p.duration
                        val c = p.currentPosition
                        if (d > 0) {
                            seek.max = d
                            seek.progress = c
                            tvPos.text = fmt(c)
                            tvDur.text = fmt(d)
                        }
                    }
                } catch (e: Exception) {
                }
            }
            if (playing != lastPlaying) {
                lastPlaying = playing
                btnToggle.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            }
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        autoMove = prefs.getBoolean("auto", true)
        PlayerService.shuffle = prefs.getBoolean("shuffle", false)
        PlayerService.repeatMode = prefs.getInt("repeat", 0)

        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        btnDownloads = findViewById(R.id.btnDownloads)
        btnLibrary = findViewById(R.id.btnLibrary)
        btnShuffle = findViewById(R.id.btnShuffle)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnToggle = findViewById(R.id.btnToggle)
        seek = findViewById(R.id.seek)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)

        adapter = AudioAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Arrastrar para reordenar (mantener pulsado) solo en "Mi música" y sin búsqueda activa
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = inLibrary && query.trim().isEmpty()

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!inLibrary || query.trim().isNotEmpty()) return false
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
                if (inLibrary && query.trim().isEmpty()) saveOrder()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        btnDownloads.setOnClickListener { inLibrary = false; refresh() }
        btnLibrary.setOnClickListener { inLibrary = true; refresh() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
            }
        })

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
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { sendToService(PlayerService.ACTION_PREV) }
        btnToggle.setOnClickListener { sendToService(PlayerService.ACTION_TOGGLE) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { sendToService(PlayerService.ACTION_NEXT) }
        findViewById<ImageButton>(R.id.btnStop).setOnClickListener { sendToService(PlayerService.ACTION_STOP) }

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
            status.text = "Falta el permiso de audio. Actívalo en Ajustes > Apps > Audio Mover."
        }
    }

    override fun onPause() {
        PlayerService.onTrackChanged = null
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    // ---------- Menú superior ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MOVE_ALL, 0, "Pasar todo a Mi música")
        val auto = menu.add(0, MENU_AUTO, 1, "Pasar automáticamente")
        auto.isCheckable = true
        auto.isChecked = autoMove
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_MOVE_ALL -> {
                if (downloads.isEmpty()) toast("No hay audios en Descargas")
                else requestMove(downloads.toList())
            }
            MENU_AUTO -> {
                autoMove = !autoMove
                item.isChecked = autoMove
                prefs.edit().putBoolean("auto", autoMove).apply()
                toast(if (autoMove) "Paso automático activado" else "Paso automático desactivado")
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
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
            MediaStore.Audio.Media.RELATIVE_PATH
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
                    val audio = Audio(id, ContentUris.withAppendedId(collection, id), name, path)
                    when {
                        path.startsWith(LIB_PATH) -> lib.add(audio)
                        path.startsWith("Download") -> dl.add(audio)
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

    private fun applyFilter() {
        val src = if (inLibrary) library else downloads
        val q = query.trim()
        shown = if (q.isEmpty()) src else src.filter { it.name.contains(q, ignoreCase = true) }.toMutableList()
        adapter.notifyDataSetChanged()
    }

    private fun refresh() {
        btnDownloads.text = "Descargas (${downloads.size})"
        btnLibrary.text = "Mi música (${library.size})"
        btnDownloads.alpha = if (inLibrary) 0.5f else 1f
        btnLibrary.alpha = if (inLibrary) 1f else 0.5f
        applyFilter()
        showNow(PlayerService.currentTitle)
    }

    private fun showNow(title: String) {
        status.text = when {
            title.isNotEmpty() -> "Reproduciendo: $title"
            inLibrary -> "Toca para reproducir. Mantén pulsado y arrastra para cambiar el orden."
            else -> "Toca para reproducir. Con los tres puntos puedes pasar a Mi música o eliminar."
        }
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
        val pm = PopupMenu(this, anchor)
        if (!inLibrary) pm.menu.add(0, 1, 0, "Pasar a Mi música")
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Adaptador ----------
    private inner class AudioAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_audio, parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = shown[position]
            h.name.text = a.name
            h.folder.text = a.folder
            h.name.setTypeface(
                null,
                if (a.name == PlayerService.currentTitle) Typeface.BOLD else Typeface.NORMAL
            )
            h.menu.setOnClickListener { showMenu(it, a) }
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) playFrom(pos)
            }
        }
    }
}
