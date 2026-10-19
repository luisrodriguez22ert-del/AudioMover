package com.example.audiomover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class LogoView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0B0B") }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val colors = intArrayOf(
        Color.parseColor("#FF5252"), Color.parseColor("#4CAF50"), Color.parseColor("#29B6F6"),
        Color.parseColor("#FFB300"), Color.parseColor("#4CAF50"), Color.parseColor("#FF5252"),
        Color.parseColor("#29B6F6")
    )
    private val heights = floatArrayOf(0.18f, 0.30f, 0.22f, 0.42f, 0.26f, 0.34f, 0.16f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 0.10f
        val pillW = 0.62f
        val above = 1.55f
        val below = 0.85f
        val unitsW = 4f + pillW + gap * 4f
        val unitsH = above + below
        val d = min(w / unitsW, h / unitsH)

        val x0 = (w - unitsW * d) / 2f
        val cy = (h - unitsH * d) / 2f + above * d

        letter.textSize = d * 0.5f
        val textY = cy - (letter.ascent() + letter.descent()) / 2f

        fun circle(x: Float, t: String) {
            canvas.drawCircle(x + d / 2f, cy, d / 2f, black)
            canvas.drawText(t, x + d / 2f, textY, letter)
        }

        var x = x0
        circle(x, "M")
        x += d + gap * d
        circle(x, "O")
        x += d + gap * d

        // Barra central (la segunda "O") con la onda de sonido
        val px = x
        val pw = pillW * d
        canvas.drawRoundRect(RectF(px, cy - above * d, px + pw, cy + below * d), pw / 2f, pw / 2f, black)
        val n = heights.size
        val inner = pw * 0.72f
        val startX = px + (pw - inner) / 2f
        wave.strokeWidth = d * 0.045f
        for (i in 0 until n) {
            val bx = startX + inner * i / (n - 1)
            wave.color = colors[i]
            val hh = heights[i] * d
            canvas.drawLine(bx, cy - hh, bx, cy + hh, wave)
        }
        x = px + pw + gap * d
        circle(x, "D")
        x += d + gap * d
        circle(x, "I")
    }
}
