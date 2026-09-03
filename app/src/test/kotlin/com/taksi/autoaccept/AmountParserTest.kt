package com.taksi.autoaccept

import com.taksi.autoaccept.core.parse.AmountParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountParserTest {

    private fun amount(text: String): Double? = AmountParser.bestAmount(text)?.value

    @Test
    fun `turkce ondalik ayirici`() {
        assertEquals(125.50, amount("Ücret ₺125,50")!!, 0.001)
        assertEquals(1250.75, amount("₺1.250,75")!!, 0.001)
        assertEquals(1250.0, amount("₺1.250")!!, 0.001)
    }

    @Test
    fun `ingilizce bicim`() {
        assertEquals(125.50, amount("125.50 TL")!!, 0.001)
        assertEquals(1250.0, amount("1,250 TL")!!, 0.001)
    }

    @Test
    fun `para birimi once veya sonra olabilir`() {
        assertEquals(240.0, amount("240 TL")!!, 0.001)
        assertEquals(240.0, amount("TL 240")!!, 0.001)
        assertEquals(240.0, amount("240₺")!!, 0.001)
    }

    @Test
    fun `mesafe ve sure tutar sanilmaz`() {
        val text = "Yolcu 2,4 km uzakta · 7 dk · Ücret ₺185,00"
        assertEquals(185.0, amount(text)!!, 0.001)
    }

    @Test
    fun `puan tutar sanilmaz`() {
        assertNull(amount("Yolcu puanı 4,8 · 3 km uzakta"))
    }

    @Test
    fun `para birimi yoksa guven dusuk kalir`() {
        val candidate = AmountParser.bestAmount("Tahmini ücret 190")
        assertEquals(190.0, candidate!!.value, 0.001)
        assertEquals(1, candidate.confidence)
    }

    @Test
    fun `ucret etiketi ve para birimi birlikteyse guven en yuksek`() {
        val candidate = AmountParser.bestAmount("Tahmini ücret: 190 TL")
        assertEquals(3, candidate!!.confidence)
    }

    @Test
    fun `gercek cagri karti metni`() {
        val text = "Yeni çağrı Kadıköy · Yolcuya 1,2 km · Tahmini kazanç ₺342,50 Kabul Et Reddet"
        assertEquals(342.50, amount(text)!!, 0.001)
    }

    @Test
    fun `birden fazla tutar varsa en guvenilir secilir`() {
        // "12" bir sayidir ama para isareti yok; 265,00 secilmeli.
        val text = "Bekleme 12 dk · Ücret ₺265,00 · Kabul Et"
        assertEquals(265.0, amount(text)!!, 0.001)
    }

    @Test
    fun `ayri metin dugumleri birlesince yanlis sayi olusmaz`() {
        // Ekranda "₺185" ve "250 puan" ayri dugumler; birlestirilince
        // aradaki bosluk binlik ayirici sanilmamali.
        assertEquals(185.0, amount("₺185 250 puan")!!, 0.001)
    }

    @Test
    fun `tutar yoksa null doner`() {
        assertNull(amount("Çevrimiçisiniz. Çağrı bekleniyor."))
    }

    @Test
    fun `normalize kenar durumlari`() {
        assertEquals(1250.0, AmountParser.normalize("1.250")!!, 0.001)
        assertEquals(12.5, AmountParser.normalize("12,5")!!, 0.001)
        assertEquals(1250000.0, AmountParser.normalize("1.250.000")!!, 0.001)
        assertNull(AmountParser.normalize("abc"))
    }

    @Test
    fun `adaylar guvene gore sirali gelir`() {
        val candidates = AmountParser.candidates("Ücret 100 · ₺250 · kazanç ₺400")
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.first().confidence >= candidates.last().confidence)
    }
}
