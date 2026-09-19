package com.taksi.autoaccept.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * Ekranin ustunde duran baslat/durdur baloncugu.
 *
 * Pencereyi erisilebilirlik servisi acar: [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * ayri bir "diger uygulamalarin uzerinde goster" izni istemez ve servis zaten
 * uygulamanin calismasi icin acik olmak zorunda. Boylece kullanicidan ikinci bir
 * izin istemeden, uygulama arka plandayken de baloncuk ekranda kalir.
 *
 * Dokunus: tek dokunus baslat/durdur, surukleyince tasinir ve en yakin yan
 * kenara yaslanir, uzun basinca uygulama acilir.
 *
 * Butun genel metotlar ana is parcacigina gonderilir; cagiran taraf
 * (ayar akisi) arka plan is parcacigindan gelir.
 */
class OverlayBubble(
    private val service: AccessibilityService,
    private val onToggle: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMoved: (x: Int, y: Int) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var iconView: TextView? = null
    private var labelView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var running = false
    private var dryRun = true

    private val size = dp(64)
    private val margin = dp(6)

    // --- Genel API --------------------------------------------------------

    /** @param saved kayitli konum; yoksa sag kenarda varsayilan yere kurulur. */
    fun show(saved: Pair<Int, Int>?) = main.post {
        if (root != null) return@post
        val (screenWidth, screenHeight) = screenSize()
        val start = saved?.let { (x, y) ->
            OverlayPlacement.clamp(x, y, screenWidth, screenHeight, size)
        } ?: OverlayPlacement.default(screenWidth, screenHeight, size, margin)

        val view = buildView()
        val lp = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = start.first
            y = start.second
        }

        // Pencere eklenemezse (uretici kisitlamasi, servis kopmus olabilir)
        // baloncuk gorunmez ama otomatik kabul calismaya devam eder.
        val added = runCatching { windowManager.addView(view, lp) }.isSuccess
        if (!added) return@post

        root = view
        params = lp
        applyState()
    }

    fun hide() = main.post {
        val view = root ?: return@post
        runCatching { windowManager.removeView(view) }
        root = null
        params = null
        iconView = null
        labelView = null
    }

    /** Baloncugun yazisini ve rengini calisma durumuna gore tazeler. */
    fun render(running: Boolean, dryRun: Boolean) = main.post {
        this.running = running
        this.dryRun = dryRun
        if (root != null) applyState()
    }

    /**
     * Baloncugu kisa sure dokunulamaz yapar.
     *
     * Kabul dugmesine jestle basildiginda dokunus ekranin en ustteki
     * penceresine gider; baloncuk o noktanin uzerindeyse basisi kendi yutar.
     * Bu yuzden basma aninda dokunuslari altindaki uygulamaya birakiyoruz.
     */
    fun pauseTouches(durationMs: Long) = main.post {
        val lp = params ?: return@post
        setTouchable(lp, false)
        main.removeCallbacks(resumeTouches)
        main.postDelayed(resumeTouches, durationMs)
    }

    private val resumeTouches = Runnable {
        val lp = params ?: return@Runnable
        setTouchable(lp, true)
    }

    private fun setTouchable(lp: WindowManager.LayoutParams, touchable: Boolean) {
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        update()
    }

    // --- Gorunum ----------------------------------------------------------

    private fun buildView(): LinearLayout {
        val icon = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f)
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }
        val label = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9f)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        iconView = icon
        labelView = label

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon)
            addView(label)
            setOnTouchListener(DragTouchListener())
        }
    }

    private fun applyState() {
        val view = root ?: return
        val color = when {
            running && dryRun -> COLOR_DRY_RUN
            running -> COLOR_RUNNING
            else -> COLOR_STOPPED
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), 0x66FFFFFF)
        }
        iconView?.text = if (running) "■" else "▶"
        labelView?.text = when {
            running && dryRun -> "DENEME"
            running -> "DURDUR"
            else -> "BAŞLAT"
        }
    }

    // --- Dokunus ----------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragTouchListener : View.OnTouchListener {

        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false

        private val longPress = Runnable {
            longPressed = true
            onLongPress()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    v.alpha = 0.75f
                    main.postDelayed(longPress, LONG_PRESS_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        val (screenWidth, screenHeight) = screenSize()
                        val (x, y) = OverlayPlacement.clamp(
                            startX + dx.toInt(),
                            startY + dy.toInt(),
                            screenWidth,
                            screenHeight,
                            size
                        )
                        lp.x = x
                        lp.y = y
                        update()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.alpha = 1f
                    when {
                        dragging -> {
                            lp.x = OverlayPlacement.snapToEdge(lp.x, screenSize().first, size, margin)
                            update()
                            onMoved(lp.x, lp.y)
                        }

                        longPressed -> Unit
                        event.actionMasked == MotionEvent.ACTION_UP -> onToggle()
                        else -> Unit
                    }
                    return true
                }
            }
            return false
        }
    }

    // --- Yardimcilar ------------------------------------------------------

    private fun update() {
        val view = root ?: return
        val lp = params ?: return
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = service.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val LONG_PRESS_MS = 600L
        private const val COLOR_STOPPED = 0xF21B5E20.toInt()
        private const val COLOR_RUNNING = 0xF2C62828.toInt()
        private const val COLOR_DRY_RUN = 0xF2E65100.toInt()
    }
}
