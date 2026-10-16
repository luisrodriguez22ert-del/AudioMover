package com.example.audiomover

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private data class Audio(val uri: Uri, val name: String, val folder: String)

    private val audios = mutableListOf<Audio>()
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private var pendingMove: Pair<Audio, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listView = findViewById(R.id.list)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btnToggle).setOnClickListener { sendToService(PlayerService.ACTION_TOGGLE) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { sendToService(PlayerService.ACTION_STOP) }

        listView.setOnItemClickListener { _, _, pos, _ -> playAudio(audios[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> askDestination(audios[pos]); true }

        val perms = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            loadAudios()
        } else {
            requestPermissions(perms, 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        loadAudios()
    }

    private fun loadAudios() {
        audios.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    audios.add(
                        Audio(
                            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: "?",
                            c.getString(2) ?: ""
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            status.text = "Falta el permiso de audio."
            return
        }
        val labels = audios.map { "${it.name}\n${it.folder}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        status.text = "${audios.size} audios. Toca = reproducir, mantener = mover."
    }

    private fun playAudio(a: Audio) {
        val i = Intent(this, PlayerService::class.java)
            .setAction(PlayerService.ACTION_PLAY)
            .setData(a.uri)
            .putExtra("title", a.name)
        startForegroundService(i)
        status.text = "Reproduciendo: ${a.name}"
    }

    private fun sendToService(action: String) {
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    private fun askDestination(a: Audio) {
        val input = EditText(this).apply { hint = "Ej: Podcasts/Favoritos" }
        AlertDialog.Builder(this)
            .setTitle("Mover a Music/...")
            .setMessage("Escribe la carpeta destino dentro de Music (se crea si no existe).")
            .setView(input)
            .setPositiveButton("Mover") { _, _ ->
                val dest = input.text.toString().trim().trim('/')
                if (dest.isNotEmpty()) requestMove(a, dest)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestMove(a: Audio, dest: String) {
        pendingMove = a to dest
        val pi = MediaStore.createWriteRequest(contentResolver, listOf(a.uri))
        startIntentSenderForResult(pi.intentSender, 100, null, 0, 0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val (a, dest) = pendingMove ?: return
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/$dest/")
                }
                contentResolver.update(a.uri, v, null, null)
                Toast.makeText(this, "Movido a Music/$dest", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo mover: ${e.message}", Toast.LENGTH_LONG).show()
            }
            pendingMove = null
            loadAudios()
        }
    }
}
