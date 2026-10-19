package com.example.audiomover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class DiscView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var cover: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var onVolumeChange: ((Float) -> Unit)? = null

    private var vol = 0.5f
    private var dragging = false
    private var cx = 0f
    private var cy = 0f
    private var u = 0f
    private var arcR = 0f
    private val d = resources.displayMetrics.density

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#222222")
        strokeWidth = 2f * d
    }
    private val arc2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8A8A85")
        strokeWidth = 1.2f * d
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#333333")
        strokeWidth = 1.5f * d
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8C8C4") }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F0F0F") }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111")
        setShadowLayer(8f * d, 0f, 3f * d, Color.parseColor("#66000000"))
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        textSize = 20f * d
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        u = min(w, h) / 2f
        cx = w / 2f - u * 0.12f
        cy = h / 2f
        arcR = u * 0.92f
    }

    fun setVolumeExternal(v: Float) {
        if (!dragging && abs(v - vol) > 0.005f) {
            vol = v
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Arco de volumen (doble línea)
        canvas.drawArc(RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR), -80f, 160f, false, arcPaint)
        val r2 = arcR - 6f * d
        canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), -80f, 160f, false, arc2Paint)

        val a0 = Math.toRadians(-80.0)
        val a1 = Math.toRadians(80.0)
        val sx = cx + arcR * cos(a0).toFloat()
        val sy = cy + arcR * sin(a0).toFloat()
        val ex = cx + arcR * cos(a1).toFloat()
        val ey = cy + arcR * sin(a1).toFloat()
        canvas.drawText("+", sx - 18f * d, sy + 6f * d, textPaint)
        canvas.drawText("−", ex - 18f * d, ey + 6f * d, textPaint)

        // Anillo y disco
        canvas.drawCircle(cx, cy, u * 0.72f, ringPaint)
        canvas.drawCircle(cx, cy, u * 0.64f, discPaint)

        // Carátula circular
        val cr = u * 0.34f
        val bmp = cover
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val s = (2f * cr) / min(bmp.width, bmp.height)
            val m = Matrix()
            m.setScale(s, s)
            m.postTranslate(cx - bmp.width * s / 2f, cy - bmp.height * s / 2f)
            shader.setLocalMatrix(m)
            coverPaint.shader = shader
            canvas.drawCircle(cx, cy, cr, coverPaint)
        } else {
            canvas.drawCircle(cx, cy, cr, blackPaint)
        }

        // Perilla del volumen (arriba = máximo, abajo = mínimo)
        val ang = Math.toRadians(80.0 - 160.0 * vol)
        canvas.drawCircle(
            cx + arcR * cos(ang).toFloat(),
            cy + arcR * sin(ang).toFloat(),
            9f * d, knobPaint
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val dx = e.x - cx
                val dy = e.y - cy
                if (dx > 0 && abs(hypot(dx, dy) - arcR) <= 36f * d) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateFrom(e)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateFrom(e)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(e)
    }

    private fun updateFrom(e: MotionEvent) {
        val deg = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat()
        val c = deg.coerceIn(-80f, 80f)
        vol = (80f - c) / 160f
        onVolumeChange?.invoke(vol)
        invalidate()
    }
}
