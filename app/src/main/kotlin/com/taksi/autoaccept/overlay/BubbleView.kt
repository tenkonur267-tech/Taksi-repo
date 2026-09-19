package com.taksi.autoaccept.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Baloncugun cizimi: degrade dolgulu daire, ince halka, yumusak golge,
 * cizilmis baslat/durdur ikonu ve altinda kisa etiket.
 *
 * Ikonlar yazi karakteri degil, yol (path) olarak cizilir: "▶" ve "■"
 * karakterlerinin gorunumu uretici yazi tipine gore degisir, cizim her
 * telefonda ayni durur.
 *
 * [diameter] gorunen dairenin capi; [pad] golgenin tasmasi icin cevresinde
 * birakilan saydam pay. Pencere ikisinin toplami kadardir, gorunen buton
 * yine [diameter] kalir.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    private val diameter: Float,
    private val pad: Float
) : View(context) {

    private var running = false
    private var dryRun = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x59FFFFFF
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        // Koseleri yuvarlatilmis ucgen/kare, keskin koselerden daha yumusak durur.
        pathEffect = CornerPathEffect(diameter * 0.05f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = diameter * 0.145f
        letterSpacing = 0.06f
    }

    private val iconPath = Path()
    private val iconRect = RectF()

    init {
        // Golge donanim hizlandirmali katmanda cizilmez.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        fill.setShadowLayer(diameter * 0.12f, 0f, diameter * 0.03f, 0x66000000)
    }

    fun setState(running: Boolean, dryRun: Boolean) {
        if (this.running == running && this.dryRun == dryRun) return
        this.running = running
        this.dryRun = dryRun
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (diameter + 2 * pad).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = diameter / 2f

        val (top, bottom) = when {
            running && dryRun -> COLOR_DRY_TOP to COLOR_DRY_BOTTOM
            running -> COLOR_RUN_TOP to COLOR_RUN_BOTTOM
            else -> COLOR_STOP_TOP to COLOR_STOP_BOTTOM
        }
        fill.shader = LinearGradient(
            cx, cy - radius, cx, cy + radius, top, bottom, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, fill)

        val ringWidth = diameter * 0.025f
        ring.strokeWidth = ringWidth
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, ring)

        // Ikon merkezden biraz yukarida: alt tarafta etiketin yeri var.
        val iconCy = cy - diameter * 0.10f
        if (running) drawStop(canvas, cx, iconCy) else drawPlay(canvas, cx, iconCy)

        canvas.drawText(labelText(), cx, cy + diameter * 0.33f, label)
    }

    /** Durdur: kosesi yuvarlatilmis kare. */
    private fun drawStop(canvas: Canvas, cx: Float, cy: Float) {
        val half = diameter * 0.115f
        iconRect.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(iconRect, diameter * 0.03f, diameter * 0.03f, icon)
    }

    /** Baslat: ucgen. Optik merkez icin biraz saga kaydirilir. */
    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float) {
        val half = diameter * 0.135f
        val left = cx - half * 0.80f + diameter * 0.015f
        val right = left + half * 1.65f
        iconPath.reset()
        iconPath.moveTo(left, cy - half)
        iconPath.lineTo(right, cy)
        iconPath.lineTo(left, cy + half)
        iconPath.close()
        canvas.drawPath(iconPath, icon)
    }

    private fun labelText(): String = when {
        running && dryRun -> "DENEME"
        running -> "DURDUR"
        else -> "BAŞLAT"
    }

    companion object {
        private val COLOR_STOP_TOP = 0xFF4ADE80.toInt()
        private val COLOR_STOP_BOTTOM = 0xFF15A34A.toInt()
        private val COLOR_RUN_TOP = 0xFFF87171.toInt()
        private val COLOR_RUN_BOTTOM = 0xFFDC2626.toInt()
        private val COLOR_DRY_TOP = 0xFFFBBF24.toInt()
        private val COLOR_DRY_BOTTOM = 0xFFD97706.toInt()
    }
}
