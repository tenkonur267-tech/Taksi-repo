package com.taksi.autoaccept.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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

    private var root: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    private var running = false
    private var dryRun = true

    /** Gorunen dairenin capi. */
    private val diameter = dp(64f)

    /** Golgenin tasmasi icin cevresindeki saydam pay; kenar boslugu da bu. */
    private val pad = dp(7f)

    /** Pencere daireden biraz buyuk: golge kirpilmasin. */
    private val windowSize = (diameter + 2 * pad).toInt()

    /**
     * Ekran kenarinda birakilan pay. Pencerenin kendi saydam payiyla birlikte
     * buton kenardan gorunur sekilde ici ceker; cihaz kenari yuvarlaksa ya da
     * sistem pencereyi birkac piksel kaydirirsa yazi yine okunur kalir.
     */
    private val edgeMargin = pad.toInt()

    // --- Genel API --------------------------------------------------------

    /** @param saved kayitli konum; yoksa sag kenarda varsayilan yere kurulur. */
    fun show(saved: Pair<Int, Int>?) = main.post {
        if (root != null) return@post
        val (screenWidth, screenHeight) = screenSize()
        val start = saved?.let { (x, y) ->
            OverlayPlacement.clamp(x, y, screenWidth, screenHeight, windowSize, edgeMargin)
        } ?: OverlayPlacement.default(screenWidth, screenHeight, windowSize, edgeMargin)

        val view = BubbleView(service, diameter, pad).apply {
            setOnTouchListener(DragTouchListener())
        }
        val lp = WindowManager.LayoutParams(
            windowSize,
            windowSize,
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
        view.setState(running, dryRun)
    }

    fun hide() = main.post {
        val view = root ?: return@post
        runCatching { windowManager.removeView(view) }
        root = null
        params = null
    }

    /** Baloncugun yazisini ve rengini calisma durumuna gore tazeler. */
    fun render(running: Boolean, dryRun: Boolean) = main.post {
        this.running = running
        this.dryRun = dryRun
        root?.setState(running, dryRun)
    }

    /** Baloncugu varsayilan kosesine geri gonderir. */
    fun moveToDefault() = main.post {
        val lp = params ?: return@post
        val (screenWidth, screenHeight) = screenSize()
        val (x, y) = OverlayPlacement.default(screenWidth, screenHeight, windowSize, edgeMargin)
        lp.x = x
        lp.y = y
        update()
    }

    /**
     * Ekran donduyse ya da pencere boyu degistiyse butonu tekrar ekranin icine
     * ceker; yoksa yatay moda gecince gorunmez bir kosede kalabilir.
     */
    fun ensureOnScreen() = main.post {
        val lp = params ?: return@post
        val (screenWidth, screenHeight) = screenSize()
        val (x, y) = OverlayPlacement.clamp(
            lp.x, lp.y, screenWidth, screenHeight, windowSize, edgeMargin
        )
        if (x == lp.x && y == lp.y) return@post
        lp.x = x
        lp.y = y
        update()
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
                    press(v, down = true)
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
                            windowSize,
                            edgeMargin
                        )
                        lp.x = x
                        lp.y = y
                        update()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    press(v, down = false)
                    when {
                        dragging -> {
                            lp.x = OverlayPlacement.snapToEdge(
                                lp.x, screenSize().first, windowSize, edgeMargin
                            )
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

        /** Basili tutulurken hafifce kuculur: dokunusun karsilik verdigi belli olsun. */
        private fun press(v: View, down: Boolean) {
            val scale = if (down) 0.92f else 1f
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(90L)
                .setInterpolator(DecelerateInterpolator())
                .start()
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

    private fun dp(value: Float): Float = value * service.resources.displayMetrics.density

    companion object {
        private const val LONG_PRESS_MS = 600L
    }
}
