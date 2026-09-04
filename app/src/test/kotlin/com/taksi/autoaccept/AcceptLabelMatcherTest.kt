package com.taksi.autoaccept

import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.core.rules.AcceptLabelMatcher
import com.taksi.autoaccept.core.rules.LabelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AcceptLabelMatcherTest {

    private val defaults = FilterSettings.DEFAULT_ACCEPT_LABELS

    @Test
    fun `birebir eslesme`() {
        assertEquals(LabelMatch.EXACT, AcceptLabelMatcher.match("Kabul Et", defaults))
        assertEquals(LabelMatch.EXACT, AcceptLabelMatcher.match("KABUL ET", defaults))
    }

    @Test
    fun `geri sayimli dugme parcali eslesir`() {
        assertEquals(LabelMatch.PARTIAL, AcceptLabelMatcher.match("KABUL ET (14)", defaults))
        assertEquals(LabelMatch.PARTIAL, AcceptLabelMatcher.match("Kabul Et · 145 TL", defaults))
    }

    @Test
    fun `iptal dugmesine asla eslesmez`() {
        // Asil hata buydu: "al" etiketi "İptal" icinde geciyordu.
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("İptal", defaults))
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("İptal Et", defaults))
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("Reddet", defaults))
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("Vazgeç", defaults))
    }

    @Test
    fun `kullanici kisa etiket eklese bile iptale basilmaz`() {
        val risky = defaults + "al"
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("İptal", risky))
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("Kapalı", risky))
        // Kisa etiket yalnizca birebir eslesir.
        assertEquals(LabelMatch.EXACT, AcceptLabelMatcher.match("Al", risky))
    }

    @Test
    fun `hem kabul hem iptal iceren kapsayici ogeye basilmaz`() {
        // Ust dugumun icerik aciklamasi iki dugmeyi birden tasiyabilir.
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("Kabul Et İptal", defaults))
    }

    @Test
    fun `varsayilan etiketlerde kisa tehlikeli etiket yok`() {
        assertFalse(defaults.any { it.length < AcceptLabelMatcher.MIN_PARTIAL_LENGTH })
    }

    @Test
    fun `alakasiz yazi eslesmez`() {
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("Ayarlar", defaults))
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("", defaults))
    }

    @Test
    fun `etiket listesi bossa eslesme olmaz`() {
        assertEquals(LabelMatch.NONE, AcceptLabelMatcher.match("Kabul Et", emptyList()))
    }
}
