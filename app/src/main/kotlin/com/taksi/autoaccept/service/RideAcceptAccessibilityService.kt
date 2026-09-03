package com.taksi.autoaccept.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.taksi.autoaccept.core.log.DecisionLog
import com.taksi.autoaccept.core.log.LogRepository
import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.core.model.RideRequest
import com.taksi.autoaccept.core.rules.Decision
import com.taksi.autoaccept.core.rules.RejectReason
import com.taksi.autoaccept.core.rules.RuleEngine
import com.taksi.autoaccept.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Hedef taksi uygulamasinin ekranini izler, yolcu cagrisi kartini tanir,
 * kurallara uyuyorsa kabul dugmesine basar.
 *
 * Bu servis hedef uygulamanin API'sine erismez; sadece ekranda zaten
 * gorunen metni okur ve kullanicinin elle yapacagi dokunmayi yapar.
 */
class RideAcceptAccessibilityService : AccessibilityService() {

    private lateinit var repository: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    private var settings: FilterSettings = FilterSettings()

    @Volatile
    private var counters = SettingsRepository.Counters(null, 0)

    /** Ayni cagri kartini saniyede onlarca kez islememek icin. */
    private var lastFingerprint: String? = null
    private var lastFingerprintAtMs = 0L
    private var lastScanAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = SettingsRepository(applicationContext)

        repository.settings
            .onEach { settings = it }
            .launchIn(scope)

        repository.counters
            .onEach { counters = it }
            .launchIn(scope)

        instanceRunning = true
        LogRepository.add(DecisionLog.info("Servis bağlandı", "Ekran izleniyor"))
    }

    override fun onDestroy() {
        instanceRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val current = settings
        if (!current.enabled) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in current.targetPackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (current.handleNotifications) handleNotification(event, pkg, current)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleScreen(pkg, current)

            else -> Unit
        }
    }

    // --- Ekran ------------------------------------------------------------

    private fun handleScreen(pkg: String, current: FilterSettings) {
        val now = System.currentTimeMillis()
        // Icerik degisimi olaylari sel gibi gelir; taramayi kisalim.
        if (now - lastScanAtMs < SCAN_INTERVAL_MS) return
        lastScanAtMs = now

        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val texts = NodeScanner.collectTexts(root)
        if (texts.isEmpty()) return

        val request = RideRequest(sourcePackage = pkg, texts = texts)
        if (isDuplicate(request.fingerprint, now)) return

        val decision = evaluate(request, now)
        if (decision is Decision.Ignore) return

        if (decision is Decision.Accept) {
            val target = NodeScanner.findAcceptTarget(root, current.acceptLabels)
            if (target == null) {
                log(Decision.Reject(RejectReason.NO_ACCEPT_BUTTON, current.acceptLabels.joinToString("/")), request)
                return
            }
            val clicked = Clicker.click(this, target)
            if (clicked) {
                onAccepted(decision, request, current)
            } else {
                LogRepository.add(
                    DecisionLog.error(
                        "Basılamadı",
                        "\"${target.label}\" bulundu ama tıklama başarısız",
                        request.flatText
                    )
                )
            }
            return
        }

        if (decision is Decision.WouldAccept) {
            val target = NodeScanner.findAcceptTarget(root, current.acceptLabels)
            log(decision, request, buttonNote(target, current))
            return
        }

        log(decision, request)
    }

    // --- Bildirim ---------------------------------------------------------

    private fun handleNotification(event: AccessibilityEvent, pkg: String, current: FilterSettings) {
        val notification = event.parcelableData as? Notification ?: return
        val extras = notification.extras
        val texts = listOfNotNull(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        ) + event.text.map { it.toString() }

        if (texts.isEmpty()) return

        val now = System.currentTimeMillis()
        val request = RideRequest(sourcePackage = pkg, texts = texts, fromNotification = true)
        if (isDuplicate(request.fingerprint, now)) return

        val decision = evaluate(request, now)
        if (decision is Decision.Ignore) return

        if (decision is Decision.Accept) {
            val action = notification.actions?.firstOrNull { action ->
                val title = action.title?.toString()?.trim()?.lowercase(TR).orEmpty()
                title.isNotEmpty() && current.acceptLabels.any { title.contains(it.trim().lowercase(TR)) }
            }
            if (action?.actionIntent == null) {
                // Bildirimde kabul dugmesi yok: uygulamayi one getirip ekrandan
                // yakalamayi bekleriz, kendi basimiza bir sey acmayiz.
                log(Decision.Reject(RejectReason.NO_ACCEPT_BUTTON, "bildirimde kabul eylemi yok"), request)
                return
            }
            runCatching { action.actionIntent.send() }
                .onSuccess { onAccepted(decision, request, current) }
                .onFailure {
                    LogRepository.add(
                        DecisionLog.error("Bildirim eylemi çalıştırılamadı", it.message.orEmpty(), request.flatText)
                    )
                }
            return
        }

        log(decision, request)
    }

    // --- Ortak ------------------------------------------------------------

    private fun evaluate(request: RideRequest, nowMs: Long): Decision {
        val engine = RuleEngine(settings)
        val context = RuleEngine.Context(
            nowMs = nowMs,
            minuteOfDay = minuteOfDay(),
            lastAcceptMs = counters.lastAcceptMs,
            acceptsToday = counters.acceptsToday
        )
        return try {
            engine.decide(request, context)
        } catch (t: Throwable) {
            Log.e(TAG, "Kural degerlendirme hatasi", t)
            LogRepository.add(DecisionLog.error("Değerlendirme hatası", t.message.orEmpty(), request.flatText))
            Decision.Ignore
        }
    }

    private fun onAccepted(decision: Decision.Accept, request: RideRequest, current: FilterSettings) {
        // Sayaci hemen bellekte de guncelle: bir sonraki olay milisaniyeler icinde gelebilir.
        val now = System.currentTimeMillis()
        counters = SettingsRepository.Counters(now, counters.acceptsToday + 1)
        scope.launch { repository.recordAccept(now) }
        if (current.vibrateOnAccept) vibrate()
        log(decision, request)
    }

    /** Deneme modunda kullaniciya kabul dugmesinin bulunup bulunmadigini bildirir. */
    private fun buttonNote(target: NodeScanner.AcceptTarget?, current: FilterSettings): String =
        if (target != null) "  [düğme: \"${target.label}\"]"
        else "  [DİKKAT: kabul düğmesi bulunamadı, etiketler: ${current.acceptLabels.joinToString("/")}]"

    private fun log(decision: Decision, request: RideRequest, note: String = "") {
        val raw = buildString {
            append(request.flatText.take(180))
            val candidates = request.amountCandidates
            if (candidates.isNotEmpty()) {
                append("  [adaylar: ")
                append(candidates.take(3).joinToString(", ") { "${RuleEngine.format(it.value)}(g${it.confidence})" })
                append("]")
            }
            append(note)
        }
        LogRepository.add(DecisionLog.from(decision, raw))
    }

    /** Ayni icerik kisa sure icinde tekrar gelirse atla. */
    private fun isDuplicate(fingerprint: String, nowMs: Long): Boolean {
        if (fingerprint == lastFingerprint && nowMs - lastFingerprintAtMs < DUPLICATE_WINDOW_MS) {
            return true
        }
        lastFingerprint = fingerprint
        lastFingerprintAtMs = nowMs
        return false
    }

    private fun minuteOfDay(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        private const val TAG = "OtoKabul"
        private const val SCAN_INTERVAL_MS = 250L
        private const val DUPLICATE_WINDOW_MS = 8_000L
        private val TR = java.util.Locale.forLanguageTag("tr")

        /** Ayarlar ekraninin servisin gercekten calisip calismadigini gostermesi icin. */
        @Volatile
        var instanceRunning: Boolean = false
            private set
    }
}
