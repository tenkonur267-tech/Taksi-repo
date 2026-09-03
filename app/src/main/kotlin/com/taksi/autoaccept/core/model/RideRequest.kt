package com.taksi.autoaccept.core.model

import com.taksi.autoaccept.core.parse.AmountParser

/** Ekrandan ya da bildirimden okunan bir yolcu cagrisi anlik goruntusu. */
data class RideRequest(
    /** Cagriyi goruldugu uygulamanin paket adi. */
    val sourcePackage: String,
    /** Ekrandaki metin dugumleri, gorunum sirasiyla. */
    val texts: List<String>,
    /** Cagri bildirimden mi geldi (ekran yerine)? */
    val fromNotification: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /** Regex'lerin uzerinde calistigi birlestirilmis metin. */
    val flatText: String = texts.joinToString(" ") { it.trim() }.replace(Regex("""\s+"""), " ")

    val amountCandidates: List<AmountParser.Candidate> by lazy { AmountParser.candidates(flatText) }

    val amount: Double? get() = amountCandidates.firstOrNull()?.value

    /** Ayni karti tekrar tekrar islememek icin icerik parmak izi. */
    val fingerprint: String get() = sourcePackage + "#" + flatText.hashCode()
}
