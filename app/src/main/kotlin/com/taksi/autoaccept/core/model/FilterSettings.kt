package com.taksi.autoaccept.core.model

/**
 * Kullanicinin belirledigi kabul kurallari.
 * Tamami saf veri: kural motoru bunu test edilebilir sekilde degerlendirir.
 */
data class FilterSettings(
    /** Ana anahtar. Kapaliyken hicbir sey yapilmaz. */
    val enabled: Boolean = false,

    /**
     * Deneme modu: kural uysa bile dugmeye BASILMAZ, sadece kayda gecer.
     * Ilk kurulumda kalibrasyon icin acik gelir.
     */
    val dryRun: Boolean = true,

    /** Izlenecek taksi uygulamalarinin paket adlari. Bos ise hicbir sey izlenmez. */
    val targetPackages: Set<String> = emptySet(),

    /** Alt sinir (TL dahil). */
    val minAmount: Double = 0.0,

    /** Ust sinir (TL dahil). 0 veya daha kucuk ise ust sinir yok. */
    val maxAmount: Double = 0.0,

    /** Yolcuya azami mesafe (km). 0 ise mesafe kontrolu yok. */
    val maxDistanceKm: Double = 0.0,

    /** Tutar okunamadiysa cagri kabul edilmez; bu bilincli bir guvenlik karari. */
    val acceptLabels: List<String> = DEFAULT_ACCEPT_LABELS,

    /** Metinde bunlardan biri gecerse cagri reddedilir. */
    val blockedKeywords: List<String> = emptyList(),

    /** Bos degilse, metinde bunlardan en az biri gecmeli. */
    val requiredKeywords: List<String> = emptyList(),

    /** Iki otomatik kabul arasinda beklenecek sure. */
    val cooldownSeconds: Int = 20,

    /** Gunluk otomatik kabul ust siniri. 0 ise sinirsiz. */
    val maxAcceptsPerDay: Int = 0,

    /** Calisma saati penceresi, gece yarisini asabilir (orn. 22:00-06:00). */
    val workingHoursEnabled: Boolean = false,
    val workStartMinute: Int = 8 * 60,
    val workEndMinute: Int = 20 * 60,

    /** Bildirim uzerinden gelen cagrilar da islensin mi? */
    val handleNotifications: Boolean = true,

    /** Otomatik kabulden sonra telefon titresin mi? */
    val vibrateOnAccept: Boolean = true
) {
    companion object {
        val DEFAULT_ACCEPT_LABELS = listOf("kabul et", "kabul", "onayla", "accept", "al")
    }
}
