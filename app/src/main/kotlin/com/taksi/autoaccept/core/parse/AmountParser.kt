package com.taksi.autoaccept.core.parse

import java.util.Locale

/**
 * Ekrandan okunan serbest metin icinden para tutarlarini cikarir.
 *
 * Zorluk su: bir yolcu cagrisi kartinda tutar disinda da sayilar bulunur
 * (mesafe "2,4 km", sure "7 dk", puan "4,8", kampanya "%20").
 * Bu yuzden her sayiyi degil, sadece guvenilir sinyali olan sayilari aliriz
 * ve her adayi bir *guven seviyesi* ile isaretleriz.
 */
object AmountParser {

    private val TR = Locale.forLanguageTag("tr")

    /**
     * Sayi govdesi: 1.250,75 / 1,250 / 125,50 / 125.50 / 1250
     *
     * Bosluk bilerek ayirici sayilmaz: ekrandaki ayri metin dugumlerini
     * bosluklarla birlestiriyoruz, "₺185 250 puan" yanlislikla 185250 olmasin.
     */
    private const val NUM = """\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

    /** Para birimi isaretleri. */
    private const val CUR = """(?:₺|tl\b|try\b|lira\b)"""

    /** Ucret anlamina gelen etiketler. */
    private const val FARE_WORD =
        """(?:ücret|ucret|tutar|kazanç|kazanc|fiyat|toplam|gelir|ödeme|odeme|hakediş|hakedis|net)"""

    /** Sayidan hemen sonra gelirse o sayinin para olmadigini gosteren birimler. */
    private val NOT_MONEY_SUFFIX =
        Regex("""^\s*(?:km|m|mt|metre|dk|dakika|sa|saat|sn|saniye|%|puan|yıldız|yildiz|kişi|kisi|adet|°)\b""")

    /** Sayidan hemen once gelirse o sayinin para olmadigini gosteren etiketler. */
    private val NOT_MONEY_PREFIX =
        Regex("""(?:mesafe|uzaklık|uzaklik|süre|sure|puan|değerlendirme|degerlendirme|%)\s*$""")

    private val reCurrencyBefore = Regex("""$CUR\s*($NUM)""", RegexOption.IGNORE_CASE)
    private val reCurrencyAfter = Regex("""($NUM)\s*$CUR""", RegexOption.IGNORE_CASE)
    private val reFareLabelled = Regex("""$FARE_WORD[^0-9₺]{0,24}($NUM)""", RegexOption.IGNORE_CASE)
    private val reFareWord = Regex(FARE_WORD)

    /**
     * @param value       normalize edilmis tutar
     * @param confidence  3 = ucret etiketi + para birimi, 2 = para birimi, 1 = sadece ucret etiketi
     * @param index       metindeki konumu (esitlikte once geleni sec)
     * @param raw         ham eslesme, kullanici kalibrasyon icin gorsun diye
     */
    data class Candidate(
        val value: Double,
        val confidence: Int,
        val index: Int,
        val raw: String
    )

    /** Metindeki butun tutar adaylarini guven seviyesine gore siralayarak dondurur. */
    fun candidates(text: String): List<Candidate> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase(TR)
        val found = LinkedHashMap<Int, Candidate>()

        fun collect(regex: Regex, baseConfidence: Int) {
            for (m in regex.findAll(lower)) {
                val group = m.groups[1] ?: continue
                val start = group.range.first
                val numText = group.value
                if (looksLikeNonMoney(lower, group.range.last + 1, start)) continue
                val value = normalize(numText) ?: continue
                if (value <= 0.0) continue
                // Ucret etiketi de yakinindaysa guven bir kademe artar.
                val boosted = if (baseConfidence == 2 && hasFareWordNear(lower, start)) 3 else baseConfidence
                val existing = found[start]
                if (existing == null || existing.confidence < boosted) {
                    found[start] = Candidate(value, boosted, start, m.value.trim())
                }
            }
        }

        collect(reCurrencyBefore, 2)
        collect(reCurrencyAfter, 2)
        collect(reFareLabelled, 1)

        return found.values.sortedWith(
            compareByDescending<Candidate> { it.confidence }.thenBy { it.index }
        )
    }

    /** En guvenilir tutar adayi; hic yoksa null. */
    fun bestAmount(text: String): Candidate? = candidates(text).firstOrNull()

    private fun looksLikeNonMoney(lower: String, afterIndex: Int, startIndex: Int): Boolean {
        val tail = lower.substring(afterIndex.coerceAtMost(lower.length))
        if (NOT_MONEY_SUFFIX.containsMatchIn(tail)) return true
        val head = lower.substring(0, startIndex)
        return NOT_MONEY_PREFIX.containsMatchIn(head)
    }

    private fun hasFareWordNear(lower: String, index: Int): Boolean {
        val from = (index - 28).coerceAtLeast(0)
        return reFareWord.containsMatchIn(lower.substring(from, index))
    }

    /**
     * "1.250,75" -> 1250.75, "125,50" -> 125.5, "1.250" -> 1250.0, "125.50" -> 125.5
     *
     * Tek ayirici varsa kural: arkasindan tam 3 hane geliyorsa binlik ayiricidir,
     * 1-2 hane geliyorsa ondalik ayiricidir.
     */
    fun normalize(raw: String): Double? {
        val s = raw.trim().replace(" ", "")
        if (s.isEmpty() || !s.any { it.isDigit() }) return null

        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')

        val cleaned = when {
            lastComma >= 0 && lastDot >= 0 -> {
                // Iki ayirici birden var: sonda olan ondaliktir.
                val decimalSep = if (lastComma > lastDot) ',' else '.'
                val groupSep = if (decimalSep == ',') '.' else ','
                s.replace(groupSep.toString(), "").replace(decimalSep, '.')
            }

            lastComma >= 0 -> splitSingle(s, lastComma)
            lastDot >= 0 -> splitSingle(s, lastDot)
            else -> s
        }

        return cleaned.toDoubleOrNull()
    }

    private fun splitSingle(s: String, sepIndex: Int): String {
        val fractionLength = s.length - sepIndex - 1
        // Birden fazla ayirici varsa hepsi binliktir: 1.250.000
        val separatorCount = s.count { it == s[sepIndex] }
        return if (fractionLength == 3 || separatorCount > 1) {
            s.replace(s[sepIndex].toString(), "")
        } else {
            s.substring(0, sepIndex) + "." + s.substring(sepIndex + 1)
        }
    }
}
