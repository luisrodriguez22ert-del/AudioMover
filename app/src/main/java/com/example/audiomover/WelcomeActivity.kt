package com.example.audiomover

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

class WelcomeActivity : Activity() {

    private var done = false
    private val handler = Handler(Looper.getMainLooper())
    private val go = Runnable { next() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Los textos y el logo aparecen uno tras otro
        val parts = listOf(R.id.w1, R.id.w2, R.id.w3, R.id.w4, R.id.logo, R.id.wName)
        parts.forEachIndexed { i, id ->
            findViewById<View>(id).apply {
                alpha = 0f
                translationY = 40f
                animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 160L).setDuration(500).start()
            }
        }

        findViewById<View>(R.id.welcomeRoot).setOnClickListener { next() }
        handler.postDelayed(go, 2800)
    }

    private fun next() {
        if (done) return
        done = true
        handler.removeCallbacks(go)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(go)
        super.onDestroy()
    }
}
