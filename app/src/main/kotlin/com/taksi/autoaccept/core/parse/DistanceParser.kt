package com.taksi.autoaccept.core.parse

import java.util.Locale

/** Cagri metnindeki mesafeyi kilometre cinsinden cikarir. */
object DistanceParser {

    private val TR = Locale.forLanguageTag("tr")

    private val reKm = Regex("""(\d+(?:[.,]\d{1,2})?)\s*km\b""")
    private val reMeter = Regex("""(\d{2,5})\s*(?:m|mt|metre)\b""")

    /** Metindeki en kucuk mesafeyi dondurur (genelde yolcuya olan uzaklik). */
    fun nearestKm(text: String): Double? {
        val lower = text.lowercase(TR)
        val values = buildList {
            reKm.findAll(lower).forEach { m ->
                AmountParser.normalize(m.groupValues[1])?.let { add(it) }
            }
            reMeter.findAll(lower).forEach { m ->
                m.groupValues[1].toDoubleOrNull()?.let { add(it / 1000.0) }
            }
        }
        return values.minOrNull()
    }
}
