package com.example.audiomover

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

private const val REQ_MOVE = 100
private const val REQ_DELETE = 101
private const val LIB_PATH = "Music/AudioMover/"

data class Audio(val id: Long, val uri: Uri, val name: String, val folder: String)

class VH(v: View) : RecyclerView.ViewHolder(v) {
    val check: CheckBox = v.findViewById(R.id.check)
    val name: TextView = v.findViewById(R.id.name)
    val folder: TextView = v.findViewById(R.id.folder)
}

class MainActivity : Activity() {

    private val downloads = mutableListOf<Audio>()
    private val library = mutableListOf<Audio>()
    private var shown: MutableList<Audio> = downloads
    private val selected = mutableSetOf<Long>()
    private var inLibrary = false
    private var pendingUris: List<Uri> = emptyList()

    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var btnDownloads: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnMove: Button
    private lateinit var adapter: AudioAdapter
    private lateinit var seek: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private var dragging = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = PlayerService.playerRef
            if (p == null) {
                if (!dragging) {
                    seek.progress = 0
                    tvPos.text = "0:00"
                    tvDur.text = "0:00"
                }
            } else if (!dragging) {
                try {
                    val d = p.duration
                    val c = p.currentPosition
                    if (d > 0) {
                        seek.max = d
                        seek.progress = c
                        tvPos.text = fmt(c)
                        tvDur.text = fmt(d)
                    }
                } catch (e: Exception) {
                }
            }
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler = findViewById(R.id.recycler)
        status = findViewById(R.id.status)
        btnDownloads = findViewById(R.id.btnDownloads)
        btnLibrary = findViewById(R.id.btnLibrary)
        btnMove = findViewById(R.id.btnMove)
        seek = findViewById(R.id.seek)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)

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

        adapter = AudioAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Arrastrar para reordenar (mantener pulsado) solo en "Mi música"
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = inLibrary

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
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
                if (inLibrary) saveOrder()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        btnDownloads.setOnClickListener { inLibrary = false; refresh() }
        btnLibrary.setOnClickListener { inLibrary = true; refresh() }

        findViewById<Button>(R.id.btnAll).setOnClickListener {
            if (selected.size == shown.size) selected.clear()
            else selected.addAll(shown.map { it.id })
            adapter.notifyDataSetChanged()
        }
        btnMove.setOnClickListener {
            val sel = shown.filter { it.id in selected }
            if (sel.isEmpty()) toast("Marca al menos un audio") else requestMove(sel)
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener {
            val sel = shown.filter { it.id in selected }
            if (sel.isEmpty()) toast("Marca al menos un audio") else requestDelete(sel)
        }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { sendToService(PlayerService.ACTION_PREV) }
        findViewById<Button>(R.id.btnToggle).setOnClickListener { sendToService(PlayerService.ACTION_TOGGLE) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { sendToService(PlayerService.ACTION_NEXT) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendToService(PlayerService.ACTION_STOP) }

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
    }

    private fun savedOrder(): List<Long> =
        (getSharedPreferences("prefs", MODE_PRIVATE).getString("order", "") ?: "")
            .split(",").mapNotNull { it.toLongOrNull() }

    private fun saveOrder() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putString("order", library.joinToString(",") { it.id.toString() })
            .apply()
    }

    private fun refresh() {
        shown = if (inLibrary) library else downloads
        selected.clear()
        btnDownloads.text = "Descargas (${downloads.size})"
        btnLibrary.text = "Mi música (${library.size})"
        btnDownloads.alpha = if (inLibrary) 0.5f else 1f
        btnLibrary.alpha = if (inLibrary) 1f else 0.5f
        btnMove.visibility = if (inLibrary) View.GONE else View.VISIBLE
        showNow(PlayerService.currentTitle)
    }

    private fun showNow(title: String) {
        status.text = when {
            title.isNotEmpty() -> "Reproduciendo: $title"
            inLibrary -> "Toca para reproducir. Mantén pulsado y arrastra para cambiar el orden."
            else -> "Marca audios y pásalos a Mi música. Toca uno para reproducir."
        }
        adapter.notifyDataSetChanged()
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
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = selected.contains(a.id)
            h.check.setOnCheckedChangeListener { _, on ->
                if (on) selected.add(a.id) else selected.remove(a.id)
            }
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) playFrom(pos)
            }
        }
    }
}
