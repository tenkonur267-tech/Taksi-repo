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
    /** Son islenen icerikler; pencere basina bir tane tutulur. */
    private val recentFingerprints = LinkedHashMap<String, Long>()
    private var lastScanAtMs = 0L
    private var lastForeignPackage: String? = null
    private var lastForeignAtMs = 0L

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
        if (pkg !in current.targetPackages) {
            // Hangi uygulamanin ekranda oldugunu gormek, yanlis paket secildiginde
            // tek ipucu; tanilama acikken bunu kaydediyoruz.
            if (current.diagnosticMode &&
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                logForeignPackage(pkg)
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (current.handleNotifications) handleNotification(event, pkg, current)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleScreen(event, pkg, current)

            else -> Unit
        }
    }

    /** Izlenmeyen bir uygulama one gecti; ayni paketi surekli tekrarlamayalim. */
    private fun logForeignPackage(pkg: String) {
        val now = System.currentTimeMillis()
        if (pkg == lastForeignPackage && now - lastForeignAtMs < FOREIGN_LOG_WINDOW_MS) return
        lastForeignPackage = pkg
        lastForeignAtMs = now
        LogRepository.add(DecisionLog.info("Ekranda: $pkg", "bu uygulama izlenmiyor"))
    }

    // --- Ekran ------------------------------------------------------------

    private fun handleScreen(event: AccessibilityEvent, pkg: String, current: FilterSettings) {
        val now = System.currentTimeMillis()
        // Icerik degisimi olaylari sel gibi gelir; taramayi kisalim. Yeni bir ekran
        // acilmasi (window state) en kritik an oldugu icin kisitlamadan muaf.
        val isNewWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isNewWindow && now - lastScanAtMs < SCAN_INTERVAL_MS) return
        lastScanAtMs = now

        val roots = candidateRoots(event, pkg)
        if (roots.isEmpty()) {
            diagnostic("Pencere okunamadı", "$pkg için erişilebilir pencere yok")
            return
        }

        for (root in roots) {
            if (scanRoot(root, pkg, current, now)) return
        }
    }

    /**
     * Cagri kartinin bulunabilecegi butun pencerelerin koklerini toplar.
     *
     * Sadece [rootInActiveWindow] yetmez: bircok surucu uygulamasi cagriyi
     * haritanin uzerine ayri bir pencerede (kaplama/diyalog) cizer ve o pencere
     * "etkin" pencere olmayabilir. O yuzden olayin kaynagini, etkin pencereyi ve
     * hedef uygulamaya ait butun pencereleri birlikte deneriz.
     */
    private fun candidateRoots(event: AccessibilityEvent, pkg: String): List<AccessibilityNodeInfo> {
        val byWindow = LinkedHashMap<Int, AccessibilityNodeInfo>()

        fun offer(node: AccessibilityNodeInfo?) {
            val root = node ?: return
            if (root.packageName?.toString() != pkg) return
            byWindow.putIfAbsent(root.windowId, root)
        }

        runCatching { event.source }.getOrNull()?.let { offer(NodeScanner.rootOf(it)) }
        runCatching { rootInActiveWindow }.getOrNull()?.let { offer(it) }
        runCatching { windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { offer(it) }
        }

        return byWindow.values.toList()
    }

    /** @return karar verildiyse true; bu pencere ise yaramadiysa false. */
    private fun scanRoot(
        root: AccessibilityNodeInfo,
        pkg: String,
        current: FilterSettings,
        now: Long
    ): Boolean {
        val texts = NodeScanner.collectTexts(root)
        if (texts.isEmpty()) return false

        val request = RideRequest(sourcePackage = pkg, texts = texts)
        if (isDuplicate(request.fingerprint, now)) return true

        val decision = evaluate(request, now)

        if (decision is Decision.Ignore) {
            // Normalde sessiz gecilir; tanilama acikken ne okundugu gorunur olmali,
            // yoksa "hicbir sey olmuyor" sikayetini teshis etmenin yolu yok.
            diagnostic("Çağrı kartı değil", request.flatText.take(200))
            return false
        }

        if (decision is Decision.Accept) {
            val target = NodeScanner.findAcceptTarget(root, current.acceptLabels)
            if (target == null) {
                log(Decision.Reject(RejectReason.NO_ACCEPT_BUTTON, current.acceptLabels.joinToString("/")), request)
                return true
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
            return true
        }

        if (decision is Decision.WouldAccept) {
            val target = NodeScanner.findAcceptTarget(root, current.acceptLabels)
            log(decision, request, buttonNote(target, current))
            return true
        }

        log(decision, request)
        return true
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

    /** Yalnizca tanilama modunda kayda gecer. */
    private fun diagnostic(title: String, detail: String) {
        if (!settings.diagnosticMode) return
        LogRepository.add(DecisionLog.info(title, detail))
    }

    /** Ayni icerik kisa sure icinde tekrar gelirse atla. */
    private fun isDuplicate(fingerprint: String, nowMs: Long): Boolean {
        recentFingerprints.entries.removeAll { nowMs - it.value >= DUPLICATE_WINDOW_MS }
        val seenAt = recentFingerprints[fingerprint]
        if (seenAt != null) return true
        if (recentFingerprints.size >= MAX_TRACKED_FINGERPRINTS) {
            recentFingerprints.remove(recentFingerprints.keys.first())
        }
        recentFingerprints[fingerprint] = nowMs
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
        private const val FOREIGN_LOG_WINDOW_MS = 30_000L
        private const val MAX_TRACKED_FINGERPRINTS = 12
        private val TR = java.util.Locale.forLanguageTag("tr")

        /** Ayarlar ekraninin servisin gercekten calisip calismadigini gostermesi icin. */
        @Volatile
        var instanceRunning: Boolean = false
            private set
    }
}
