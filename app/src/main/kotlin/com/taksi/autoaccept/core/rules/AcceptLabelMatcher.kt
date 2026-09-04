package com.taksi.autoaccept.core.rules

import java.util.Locale

enum class LabelMatch { EXACT, PARTIAL, NONE }

/**
 * Bir ekran ogesinin yazisinin kabul dugmesine ait olup olmadigina karar verir.
 *
 * Parcali eslesme sart: dugmede cogu zaman geri sayim da yazar
 * ("KABUL ET (14)"). Ama parcali eslesme tehlikelidir de: "al" gibi kisa bir
 * etiket "İptal" icinde gecer ve yanlislikla iptal dugmesine basiliabilir.
 * Bu yuzden iki koruma var:
 *
 *  1. Kisa etiketler (4 harften az) yalnizca birebir eslesir.
 *  2. Yazisinda iptal/reddet gecen bir oge asla kabul hedefi sayilmaz.
 */
object AcceptLabelMatcher {

    private val TR: Locale = Locale.forLanguageTag("tr")

    /** Bundan kisa etiketlerle parcali eslesme yapilmaz. */
    const val MIN_PARTIAL_LENGTH = 4

    /** Bu kelimeleri iceren bir ogeye hicbir kosulda basilmaz. */
    val CANCEL_WORDS = listOf(
        "iptal", "reddet", "red et", "vazgeç", "vazgec",
        "cancel", "reject", "decline", "hayır", "hayir", "geç", "atla"
    )

    fun match(nodeLabel: String, acceptLabels: List<String>): LabelMatch {
        val node = nodeLabel.trim().lowercase(TR)
        if (node.isEmpty()) return LabelMatch.NONE

        val labels = acceptLabels.map { it.trim().lowercase(TR) }.filter { it.isNotEmpty() }
        if (labels.isEmpty()) return LabelMatch.NONE

        if (labels.any { it == node }) return LabelMatch.EXACT

        // Birebir eslesmiyorsa parcali bakariz; once iptal kelimesi var mi diye.
        if (containsCancelWord(node)) return LabelMatch.NONE

        val partial = labels.any { it.length >= MIN_PARTIAL_LENGTH && node.contains(it) }
        return if (partial) LabelMatch.PARTIAL else LabelMatch.NONE
    }

    fun containsCancelWord(lowercaseText: String): Boolean =
        CANCEL_WORDS.any { lowercaseText.contains(it) }
}
