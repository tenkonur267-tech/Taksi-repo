package com.taksi.autoaccept.core.rules

/** Bir cagri icin verilen karar. */
sealed interface Decision {

    /** Kural uydu, kabul dugmesine basilmali. */
    data class Accept(val amount: Double, val distanceKm: Double?) : Decision

    /** Kural uydu ama deneme modu acik: sadece kaydedilir. */
    data class WouldAccept(val amount: Double, val distanceKm: Double?) : Decision

    /** Kural uymadi. */
    data class Reject(val reason: RejectReason, val detail: String = "") : Decision

    /** Bu ekran/bildirim bir yolcu cagrisi degil, sessizce gecilir. */
    data object Ignore : Decision
}

enum class RejectReason(val label: String) {
    SERVICE_DISABLED("Servis kapalı"),
    PACKAGE_NOT_WATCHED("Uygulama izlenmiyor"),
    NO_AMOUNT("Tutar okunamadı"),
    LOW_CONFIDENCE("Tutar güvenilir değil"),
    BELOW_MIN("Alt sınırın altında"),
    ABOVE_MAX("Üst sınırın üstünde"),
    TOO_FAR("Mesafe çok uzak"),
    BLOCKED_KEYWORD("Yasaklı kelime"),
    MISSING_REQUIRED_KEYWORD("Zorunlu kelime yok"),
    OUTSIDE_HOURS("Çalışma saati dışında"),
    COOLDOWN("Bekleme süresi dolmadı"),
    DAILY_LIMIT("Günlük limit doldu"),
    NO_ACCEPT_BUTTON("Kabul düğmesi bulunamadı")
}
