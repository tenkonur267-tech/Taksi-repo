package com.taksi.autoaccept.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.taksi.autoaccept.core.log.DecisionLog
import com.taksi.autoaccept.core.log.LogRepository
import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.core.model.RideRequest
import com.taksi.autoaccept.core.rules.Decision
import com.taksi.autoaccept.core.rules.RejectReason
import com.taksi.autoaccept.core.rules.RuleEngine
import com.taksi.autoaccept.data.SettingsRepository
import com.taksi.autoaccept.overlay.OverlayBubble
import com.taksi.autoaccept.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    /** Durum bildirimini bu servis oturumunda ayaga kaldirdik mi? */
    private var foregroundRequested = false

    /** Ekranin ustunde duran baslat/durdur baloncugu. */
    private var overlay: OverlayBubble? = null

    @Volatile
    private var overlayPosition: Pair<Int, Int>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = SettingsRepository(applicationContext)

        repository.settings
            .onEach { next ->
                val wasEnabled = settings.enabled
                settings = next
                // Kapalidan aciga geciste durum bildirimini ayaga kaldir. Bu,
                // yeniden baslatma ya da surecin oldurulmesi sonrasi ilk okumayi
                // da kapsar. Arka plandan baslatma engellenirse yutulur; kabul
                // islevi bu servise bagli degil.
                if (next.enabled && (!wasEnabled || !foregroundRequested)) {
                    foregroundRequested = true
                    AutoAcceptForegroundService.start(applicationContext)
                } else if (!next.enabled) {
                    foregroundRequested = false
                }
            }
            .launchIn(scope)

        repository.counters
            .onEach { counters = it }
            .launchIn(scope)

        observeOverlay()

        instanceRunning = true
        LogRepository.add(DecisionLog.info("Servis bağlandı", "Ekran izleniyor"))
    }

    override fun onDestroy() {
        instanceRunning = false
        overlay?.hide()
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Ekran ustu baloncuk ----------------------------------------------

    /**
     * Baloncugu kurar ve ayarlara bagli tutar.
     *
     * Pencereyi bu servis acar: erisilebilirlik kaplamasi ayri bir izin
     * istemez ve servis zaten acik olmak zorunda oldugu icin baloncuk,
     * uygulama arka plandayken de ekranda kalir.
     */
    private fun observeOverlay() {
        scope.launch {
            // Once son birakildigi yeri oku; yoksa varsayilan koseye kurulur.
            overlayPosition = runCatching { repository.overlayPosition.first() }.getOrNull()

            val bubble = OverlayBubble(
                service = this@RideAcceptAccessibilityService,
                onToggle = ::toggleFromOverlay,
                onLongPress = ::openApp,
                onMoved = { x, y ->
                    overlayPosition = x to y
                    scope.launch { repository.saveOverlayPosition(x, y) }
                }
            )
            overlay = bubble

            // Konum disaridan silinirse (Ayarlar'daki "sag kenara al")
            // baloncuk varsayilan kosesine doner.
            repository.overlayPosition
                .onEach { saved ->
                    overlayPosition = saved
                    if (saved == null) bubble.moveToDefault()
                }
                .launchIn(scope)

            // show/hide kendi icinde tekrar cagrilmaya dayanikli; her ayar
            // degisiminde durumu yeniden uygulamak yeterli.
            repository.settings
                .onEach { next ->
                    if (next.overlayEnabled) {
                        bubble.show(overlayPosition)
                        bubble.render(running = next.enabled, dryRun = next.dryRun)
                    } else {
                        bubble.hide()
                    }
                }
                .launchIn(scope)
        }
    }

    /** Baloncuga dokunuldu: tek anahtari cevir. */
    private fun toggleFromOverlay() {
        val current = settings
        if (!current.enabled && current.targetPackages.isEmpty()) {
            // Hicbir uygulama secilmemisken baslatmak sessiz bir hayal kirikligi
            // olurdu: baloncuk yesilden kirmiziya doner ama hicbir sey izlenmez.
            Toast.makeText(
                this,
                "Önce uygulamadan izlenecek taksi uygulamasını seçin",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val next = !current.enabled
        // Bellekteki kopyayi hemen guncelle: depoya yazma asenkron, kullanici
        // iki kez dokunursa ayni karari tekrarlamasin.
        settings = current.copy(enabled = next)
        scope.launch { repository.update { it.copy(enabled = next) } }
        vibrate(TOGGLE_VIBRATE_MS)
        LogRepository.add(
            DecisionLog.info(
                if (next) "Baloncuktan başlatıldı" else "Baloncuktan durduruldu",
                if (next && current.dryRun) "deneme modu açık: düğmeye basılmaz" else ""
            )
        )
    }

    /** Ekran dondu ya da pencere boyu degisti: butonu ekranin icinde tut. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.ensureOnScreen()
    }

    /** Baloncuga uzun basildi: ayarlar ekranini one getir. */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
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
        // Kendi baloncugumuz da pencere olayi uretir; onu "izlenmiyor" diye
        // kaydetmek tanilama kaydini yaniltici sekilde doldurur.
        if (pkg == packageName) return
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

        val source = runCatching { event.source }.getOrNull()
        source?.let { offer(NodeScanner.rootOf(it)) }
        runCatching { rootInActiveWindow }.getOrNull()?.let { offer(it) }
        runCatching { windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { offer(it) }
        }

        // Cagri bildirim golgesinde ya da baska bir surecin cizdigi bir
        // pencerede duruyorsa o pencerenin koku sistem arayuzune ait olur ve
        // paket suzgecine takilir. Olayin kaynagi yine de aradigimiz agactir;
        // baska hicbir kok bulunamadiysa onu tariyoruz.
        if (byWindow.isEmpty() && source != null) return listOf(source)

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
        // Bu pencereyi az once isledik; ama cagri baska bir pencerede olabilir,
        // o yuzden taramayi burada bitirmeyiz. Eskiden hic degismeyen ana sayfa
        // penceresi her olayda taramayi kesiyor, asil cagri karti hic
        // taranmiyordu.
        if (isDuplicate(request.fingerprint, now)) return false

        val decision = evaluate(request, now)

        if (decision is Decision.Ignore) {
            // Normalde sessiz gecilir; tanilama acikken ne okundugu gorunur olmali,
            // yoksa "hicbir sey olmuyor" sikayetini teshis etmenin yolu yok.
            // Ekranda para var ama kabul dugmesinin yazisi yoksa, sorun buyuk
            // ihtimalle o yazinin ayarlardakiyle uyusmamasi; bunu ayirt edelim.
            if (RuleEngine.hasMoneyMarker(request.flatText)) {
                diagnostic(
                    "Kabul düğmesinin yazısı bulunamadı",
                    "aranan: ${current.acceptLabels.joinToString("/")} · ekran: ${request.flatText.take(200)}"
                )
            } else {
                diagnostic("Çağrı kartı değil", request.flatText.take(200))
            }
            return false
        }

        if (decision is Decision.Accept) {
            val target = NodeScanner.findAcceptTarget(root, current.acceptLabels)
            if (target == null) {
                // Dugme bir an sonra ciziliyor olabilir: kartin parmak izini
                // unutuyoruz ki bir sonraki olayda yeniden degerlendirilsin.
                // Kayda ise kart basina bir kez dusuyoruz, yoksa kart ekranda
                // durdugu surece kayitlar dolar.
                forget(request.fingerprint)
                if (!isDuplicate(NO_BUTTON_KEY + request.fingerprint, now)) {
                    log(
                        Decision.Reject(
                            RejectReason.NO_ACCEPT_BUTTON,
                            current.acceptLabels.joinToString("/")
                        ),
                        request,
                        clickableNote(root)
                    )
                }
                // Cagri baska bir pencerede olabilir; eskiden burada durulurdu
                // ve asil kart hic taranmazdi.
                return false
            }
            // Jestle basilirken baloncuk kabul dugmesinin uzerinde duruyorsa
            // dokunusu kendi yutar; kisa sureligine dokunulamaz yapiyoruz.
            overlay?.pauseTouches(CLICK_TOUCH_PAUSE_MS)
            val clicked = Clicker.click(this, target)
            if (clicked) {
                onAccepted(decision, request, current)
            } else {
                // Basma denemesi baslatilamadi: kart hala ekrandaysa bir
                // sonraki olayda yeniden denensin.
                forget(request.fingerprint)
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
            log(decision, request, buttonNote(target, current, root))
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
    private fun buttonNote(
        target: NodeScanner.AcceptTarget?,
        current: FilterSettings,
        root: AccessibilityNodeInfo
    ): String =
        if (target != null) "  [düğme: \"${target.label}\"]"
        else "  [DİKKAT: kabul düğmesi bulunamadı, etiketler: " +
            "${current.acceptLabels.joinToString("/")}]${clickableNote(root)}"

    /**
     * Ekrandaki tiklanabilir ogelerin yazilari.
     *
     * Dugme bulunamadiginda kullaniciya gereken tek sey bu: ayardaki yaziyi
     * neye gore duzeltecegini gosterir.
     */
    private fun clickableNote(root: AccessibilityNodeInfo): String {
        val labels = runCatching { NodeScanner.clickableLabels(root) }.getOrNull().orEmpty()
        if (labels.isEmpty()) return ""
        return "  [ekrandaki düğmeler: ${labels.joinToString(" · ") { "\"$it\"" }}]"
    }

    private fun log(decision: Decision, request: RideRequest, note: String = "") {
        // Tutar okunamadiginda ya da dugme bulunamadiginda ekrandan ne
        // geldigini gormek tek ipucu; o durumda metnin daha uzunu kaydedilir.
        val limit = if (decision is Decision.Reject && decision.reason in VERBOSE_REASONS) 400 else 180
        val raw = buildString {
            append(request.flatText.take(limit))
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

    /** Karar verilemedi: ayni icerik bir sonraki olayda yeniden denensin. */
    private fun forget(fingerprint: String) {
        recentFingerprints.remove(fingerprint)
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

    private fun vibrate(durationMs: Long = ACCEPT_VIBRATE_MS) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    companion object {
        private const val TAG = "OtoKabul"
        private const val SCAN_INTERVAL_MS = 250L
        private const val DUPLICATE_WINDOW_MS = 8_000L
        private const val FOREIGN_LOG_WINDOW_MS = 30_000L
        private const val MAX_TRACKED_FINGERPRINTS = 16

        /** Dugmesi bulunamayan kartlarin kayit tekrarini onlemek icin. */
        private const val NO_BUTTON_KEY = "nobtn:" 
        private const val ACCEPT_VIBRATE_MS = 200L
        private const val TOGGLE_VIBRATE_MS = 40L
        private const val CLICK_TOUCH_PAUSE_MS = 500L
        private val VERBOSE_REASONS = setOf(
            RejectReason.NO_AMOUNT,
            RejectReason.LOW_CONFIDENCE,
            RejectReason.NO_ACCEPT_BUTTON
        )
        private val TR = java.util.Locale.forLanguageTag("tr")

        /** Ayarlar ekraninin servisin gercekten calisip calismadigini gostermesi icin. */
        @Volatile
        var instanceRunning: Boolean = false
            private set
    }
}
