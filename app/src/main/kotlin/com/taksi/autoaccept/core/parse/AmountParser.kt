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
     * Gercekten sayinin icinde gecen bosluklar [prepare] icinde temizlenir.
     */
    private const val NUM = """\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

    /** Turkce kucuk harfler; kelime siniri kontrollerinde kullanilir. */
    private const val LETTER = """[a-zçğıöşü]"""

    private const val CUR_WORD = """(?:tl|try|lira)"""

    /**
     * Sayidan once gelen para birimi: "₺250", "TL 250", "TL250".
     * Basindaki harf kontrolu sart: "atl 250" gibi bir kelimenin sonu
     * para birimi sayilmamali.
     */
    private const val CUR_BEFORE = """(?:₺|(?<!$LETTER)$CUR_WORD(?!$LETTER))"""

    /**
     * Sayidan sonra gelen para birimi: "250 TL", "250₺", "250TL".
     * Onunde zaten rakam var; sadece sonrasina bakmak yeter ("250 tlf" olmasin).
     */
    private const val CUR_AFTER = """(?:₺|$CUR_WORD(?!$LETTER))"""

    /**
     * Ucret anlamina gelen etiketler; govde olarak yazilir ki cekim ekleri de
     * kapsansin ("kazanç", "kazanacağınız", "ödeme", "ödenecek").
     */
    private const val FARE_WORD =
        """(?:ücret|ucret|tutar|kazan|fiyat|toplam|gelir|öde|ode|hakediş|hakedis|bedel|ciro|tarife|net|fare|price|total|earning|payout)"""

    /** Sayidan hemen sonra gelirse o sayinin para olmadigini gosteren birimler. */
    private val NOT_MONEY_SUFFIX =
        Regex("""^\s*(?:km|m|mt|metre|dk|dakika|sa|saat|sn|saniye|%|puan|yıldız|yildiz|kişi|kisi|adet|°)\b""")

    /** Sayidan hemen once gelirse o sayinin para olmadigini gosteren etiketler. */
    private val NOT_MONEY_PREFIX =
        Regex("""(?:mesafe|uzaklık|uzaklik|süre|sure|puan|değerlendirme|degerlendirme|%)\s*$""")

    private val reCurrencyBefore = Regex("""$CUR_BEFORE\s*($NUM)""")
    private val reCurrencyAfter = Regex("""($NUM)\s*$CUR_AFTER""")

    // Etiket ile sayi arasinda baska bir rakam ya da para isareti olmamali.
    private val reFareLabelled = Regex("""(?<!$LETTER)$FARE_WORD[^0-9₺]{0,24}($NUM)""")
    private val reFareWord = Regex("""(?<!$LETTER)$FARE_WORD""")

    /** Sayinin icindeki bolunmez bosluklar: "1 250,75" tek bir sayidir. */
    private val NBSP_IN_NUMBER = Regex("""(\d)[\u00A0\u202F\u2009\u2007](\d)""")

    /**
     * Duz bosluklu binlik ayirma yalnizca kurus haneli yazimda birlestirilir:
     * "1 250,75" tutardir, "185 250 puan" iki ayri sayidir.
     */
    private val SPACE_GROUPED_NUMBER = Regex("""(\d{1,3}) (\d{3}[.,]\d{2})(?!\d)""")

    /** Kurus haneli yazim: para birimi gorunmese de tutar oldugunun isareti. */
    private val TWO_DECIMALS = Regex("""[.,]\d{2}$""")

    /** Etiketle sayi arasinda yalnizca bunlar varsa etiket o sayiya aittir. */
    private val TIGHT_GAP = Regex("""[\s:·•~≈\-–—()\[\]]*""")

    /**
     * @param value       normalize edilmis tutar
     * @param confidence  3 = ucret etiketi + para birimi, 2 = para birimi
     *                    (ya da ucret etiketi + kurus haneli yazim),
     *                    1 = sadece ucret etiketi
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
        val lower = prepare(text)
        val found = LinkedHashMap<Int, Candidate>()

        /**
         * @param vetoUnits sayinin yanindaki "km/dk/puan" gibi birimler adayi
         *   elesin mi? Para birimi goren eslesmelerde elenmez: "₺185 M. Kemal"
         *   ya da "₺185 · %20 kampanya" gecerli bir tutardir, yanindaki harf
         *   ne olursa olsun.
         */
        fun collect(regex: Regex, baseConfidence: Int, vetoUnits: Boolean) {
            for (m in regex.findAll(lower)) {
                val group = m.groups[1] ?: continue
                val start = group.range.first
                val numText = group.value
                if (vetoUnits && looksLikeNonMoney(lower, group.range.last + 1, start)) continue
                val value = normalize(numText) ?: continue
                if (value <= 0.0) continue

                val confidence = when {
                    // Ucret etiketi de yakinindaysa guven bir kademe artar.
                    baseConfidence == 2 && hasFareWordNear(lower, start) -> 3
                    // Para birimini simge olarak cizen uygulamalarda ekranda hic
                    // "₺" metni olmaz. Etiket sayinin hemen onundeyse ve sayi
                    // kurus haneli yaziliyorsa ("Tahmini ücret: 342,50") bunu
                    // tutar saymak guvenli: kural motoru zaten yalnizca kabul
                    // dugmesi ya da para isareti goren ekranlarda calisir.
                    baseConfidence == 1 && TWO_DECIMALS.containsMatchIn(numText) &&
                        isTightFareGap(m.value, numText) -> 2

                    else -> baseConfidence
                }

                val existing = found[start]
                if (existing == null || existing.confidence < confidence) {
                    found[start] = Candidate(value, confidence, start, m.value.trim())
                }
            }
        }

        collect(reCurrencyBefore, 2, vetoUnits = false)
        collect(reCurrencyAfter, 2, vetoUnits = false)
        collect(reFareLabelled, 1, vetoUnits = true)

        return found.values.sortedWith(
            compareByDescending<Candidate> { it.confidence }.thenBy { it.index }
        )
    }

    /** En guvenilir tutar adayi; hic yoksa null. */
    fun bestAmount(text: String): Candidate? = candidates(text).firstOrNull()

    /** Kucuk harfe cevirir ve sayinin icine dusmus bosluklari kapatir. */
    private fun prepare(text: String): String {
        var s = text.lowercase(TR)
        s = replaceUntilStable(s, NBSP_IN_NUMBER)
        s = replaceUntilStable(s, SPACE_GROUPED_NUMBER)
        return s
    }

    /** Eslesmeler ic ice gectigi icin ("1 250 000,50") degisim durana kadar. */
    private fun replaceUntilStable(text: String, regex: Regex): String {
        var current = text
        while (true) {
            val next = regex.replace(current, "$1$2")
            if (next == current) return current
            current = next
        }
    }

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

    /** Etiket ile sayi arasinda yalnizca ayirac/bosluk mu var? */
    private fun isTightFareGap(match: String, numText: String): Boolean {
        val head = match.dropLast(numText.length)
        val wordEnd = reFareWord.find(head)?.range?.last?.plus(1) ?: return false
        val gap = head.substring(wordEnd)
        return gap.length <= 12 && TIGHT_GAP.matches(gap)
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
