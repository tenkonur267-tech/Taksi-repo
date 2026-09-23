package com.taksi.autoaccept

import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.core.model.RideRequest
import com.taksi.autoaccept.core.rules.Decision
import com.taksi.autoaccept.core.rules.RejectReason
import com.taksi.autoaccept.core.rules.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val pkg = "com.example.taksi"

    private fun settings(
        min: Double = 100.0,
        max: Double = 300.0,
        dryRun: Boolean = false,
        block: List<String> = emptyList(),
        required: List<String> = emptyList(),
        maxDistanceKm: Double = 0.0,
        cooldown: Int = 0,
        dailyLimit: Int = 0,
        hours: Boolean = false,
        start: Int = 8 * 60,
        end: Int = 20 * 60
    ) = FilterSettings(
        enabled = true,
        dryRun = dryRun,
        targetPackages = setOf(pkg),
        minAmount = min,
        maxAmount = max,
        maxDistanceKm = maxDistanceKm,
        blockedKeywords = block,
        requiredKeywords = required,
        cooldownSeconds = cooldown,
        maxAcceptsPerDay = dailyLimit,
        workingHoursEnabled = hours,
        workStartMinute = start,
        workEndMinute = end
    )

    private fun request(vararg texts: String, pkgName: String = pkg) =
        RideRequest(sourcePackage = pkgName, texts = texts.toList())

    private fun ctx(
        nowMs: Long = 1_000_000L,
        minuteOfDay: Int = 12 * 60,
        lastAcceptMs: Long? = null,
        acceptsToday: Int = 0
    ) = RuleEngine.Context(nowMs, minuteOfDay, lastAcceptMs, acceptsToday)

    @Test
    fun `para birimi rakama yapisik yazilsa da cagri karti taninir`() {
        // Kabul dugmesinin yazisi ayarlardakinden farkli olsa bile "240TL"
        // bir para isaretidir; kart islenmeli.
        val decision = RuleEngine(settings())
            .decide(request("Yeni çağrı", "240TL", "Onayla"), ctx())
        assertTrue(decision is Decision.Accept)
        assertEquals(240.0, (decision as Decision.Accept).amount, 0.001)
    }

    @Test
    fun `aralik icindeki cagri kabul edilir`() {
        val decision = RuleEngine(settings())
            .decide(request("Yeni çağrı", "₺185,00", "Kabul Et"), ctx())
        assertTrue(decision is Decision.Accept)
        assertEquals(185.0, (decision as Decision.Accept).amount, 0.001)
    }

    @Test
    fun `alt sinirin altindaki cagri reddedilir`() {
        val decision = RuleEngine(settings())
            .decide(request("₺80,00", "Kabul Et"), ctx())
        assertEquals(RejectReason.BELOW_MIN, (decision as Decision.Reject).reason)
    }

    @Test
    fun `ust sinirin ustundeki cagri reddedilir`() {
        val decision = RuleEngine(settings())
            .decide(request("₺450,00", "Kabul Et"), ctx())
        assertEquals(RejectReason.ABOVE_MAX, (decision as Decision.Reject).reason)
    }

    @Test
    fun `ust sinir sifirsa sinirsizdir`() {
        val decision = RuleEngine(settings(max = 0.0))
            .decide(request("₺9.999,00", "Kabul Et"), ctx())
        assertTrue(decision is Decision.Accept)
    }

    @Test
    fun `sinir degerleri dahildir`() {
        val engine = RuleEngine(settings(min = 100.0, max = 300.0))
        assertTrue(engine.decide(request("₺100", "Kabul Et"), ctx()) is Decision.Accept)
        assertTrue(engine.decide(request("₺300", "Kabul Et"), ctx()) is Decision.Accept)
    }

    @Test
    fun `deneme modunda tiklama kararina donusmez`() {
        val decision = RuleEngine(settings(dryRun = true))
            .decide(request("₺185,00", "Kabul Et"), ctx())
        assertTrue(decision is Decision.WouldAccept)
    }

    @Test
    fun `servis kapaliysa hicbir sey kabul edilmez`() {
        val decision = RuleEngine(settings().copy(enabled = false))
            .decide(request("₺185,00", "Kabul Et"), ctx())
        assertEquals(RejectReason.SERVICE_DISABLED, (decision as Decision.Reject).reason)
    }

    @Test
    fun `izlenmeyen uygulama atlanir`() {
        val decision = RuleEngine(settings())
            .decide(request("₺185,00", "Kabul Et", pkgName = "com.baska.app"), ctx())
        assertEquals(RejectReason.PACKAGE_NOT_WATCHED, (decision as Decision.Reject).reason)
    }

    @Test
    fun `cagri karti degilse yok sayilir`() {
        val decision = RuleEngine(settings())
            .decide(request("Ana sayfa", "Profil", "Ayarlar"), ctx())
        assertTrue(decision is Decision.Ignore)
    }

    @Test
    fun `tutar okunamayan cagri kabul edilmez`() {
        val decision = RuleEngine(settings())
            .decide(request("Yeni çağrı", "Kabul Et"), ctx())
        assertEquals(RejectReason.NO_AMOUNT, (decision as Decision.Reject).reason)
    }

    @Test
    fun `para birimi olmayan sayiyla kabul edilmez`() {
        val decision = RuleEngine(settings())
            .decide(request("Tahmini ücret 185", "Kabul Et"), ctx())
        assertEquals(RejectReason.LOW_CONFIDENCE, (decision as Decision.Reject).reason)
    }

    @Test
    fun `yasakli kelime cagriyi reddeder`() {
        val decision = RuleEngine(settings(block = listOf("havalimanı")))
            .decide(request("Havalimanı transferi ₺185,00", "Kabul Et"), ctx())
        assertEquals(RejectReason.BLOCKED_KEYWORD, (decision as Decision.Reject).reason)
    }

    @Test
    fun `zorunlu kelime yoksa reddedilir`() {
        val decision = RuleEngine(settings(required = listOf("nakit")))
            .decide(request("Kredi kartı ₺185,00", "Kabul Et"), ctx())
        assertEquals(RejectReason.MISSING_REQUIRED_KEYWORD, (decision as Decision.Reject).reason)
    }

    @Test
    fun `uzak cagri reddedilir`() {
        val decision = RuleEngine(settings(maxDistanceKm = 3.0))
            .decide(request("Yolcuya 7,5 km", "₺185,00", "Kabul Et"), ctx())
        assertEquals(RejectReason.TOO_FAR, (decision as Decision.Reject).reason)
    }

    @Test
    fun `bekleme suresi dolmadan ikinci kabul olmaz`() {
        val decision = RuleEngine(settings(cooldown = 30))
            .decide(request("₺185,00", "Kabul Et"), ctx(nowMs = 100_000L, lastAcceptMs = 90_000L))
        assertEquals(RejectReason.COOLDOWN, (decision as Decision.Reject).reason)
    }

    @Test
    fun `bekleme suresi dolunca kabul edilir`() {
        val decision = RuleEngine(settings(cooldown = 30))
            .decide(request("₺185,00", "Kabul Et"), ctx(nowMs = 200_000L, lastAcceptMs = 90_000L))
        assertTrue(decision is Decision.Accept)
    }

    @Test
    fun `gunluk limit dolunca reddedilir`() {
        val decision = RuleEngine(settings(dailyLimit = 5))
            .decide(request("₺185,00", "Kabul Et"), ctx(acceptsToday = 5))
        assertEquals(RejectReason.DAILY_LIMIT, (decision as Decision.Reject).reason)
    }

    @Test
    fun `calisma saati disinda reddedilir`() {
        val decision = RuleEngine(settings(hours = true, start = 8 * 60, end = 20 * 60))
            .decide(request("₺185,00", "Kabul Et"), ctx(minuteOfDay = 22 * 60))
        assertEquals(RejectReason.OUTSIDE_HOURS, (decision as Decision.Reject).reason)
    }

    @Test
    fun `gece yarisini asan calisma penceresi`() {
        // 22:00 - 06:00
        val engine = RuleEngine(settings(hours = true, start = 22 * 60, end = 6 * 60))
        assertTrue(engine.decide(request("₺185", "Kabul Et"), ctx(minuteOfDay = 23 * 60)) is Decision.Accept)
        assertTrue(engine.decide(request("₺185", "Kabul Et"), ctx(minuteOfDay = 2 * 60)) is Decision.Accept)
        val gunduz = engine.decide(request("₺185", "Kabul Et"), ctx(minuteOfDay = 12 * 60))
        assertEquals(RejectReason.OUTSIDE_HOURS, (gunduz as Decision.Reject).reason)
    }
}
