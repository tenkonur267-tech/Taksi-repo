package com.taksi.autoaccept.core.parse

import java.util.Locale

/** Cagri metnindeki mesafeyi kilometre cinsinden cikarir. */
object DistanceParser {

    private val TR = Locale.forLanguageTag("tr")

    private val reKm = Regex("""(\d+(?:[.,]\d{1,2})?)\s*km\b""")
    private val reMeter = Regex("""(\d{2,5})\s*(?:m|mt|metre)\b""")

    /**
     * Kartta ilk yazan mesafeyi dondurur: yolcuya olan uzaklik.
     *
     * Cagri kartlari once yolcuya gitme suresini/mesafesini, sonra yolculugun
     * kendisini yazar:
     *
     * ```
     * 8 dk · 2,81 km   Köşklü Çeşme Mah.   <- yolcuya uzaklik
     * 4 dk · 1,45 km   Mevlana Mah.        <- yolculuk
     * ```
     *
     * Eskiden en kucuk deger alinirdi; bu ornekte 1,45 km cikiyor ve
     * "azami mesafe 2 km" diyen bir surucu 2,81 km uzaktaki yolcuyu kabul
     * etmis oluyordu. Ayardaki mesafe yolcuya uzaklik demek oldugu icin
     * metinde ilk gecen deger dogru olanidir.
     */
    fun pickupKm(text: String): Double? {
        val lower = text.lowercase(TR)
        var best: Pair<Int, Double>? = null

        fun offer(index: Int, km: Double) {
            if (km <= 0.0) return
            if (best == null || index < best!!.first) best = index to km
        }

        reKm.findAll(lower).forEach { m ->
            AmountParser.normalize(m.groupValues[1])?.let { offer(m.range.first, it) }
        }
        reMeter.findAll(lower).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let { offer(m.range.first, it / 1000.0) }
        }

        return best?.second
    }
}
