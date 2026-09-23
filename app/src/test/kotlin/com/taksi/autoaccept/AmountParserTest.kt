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

    // --- Gercek ekranlardan gelen zor durumlar -----------------------------

    @Test
    fun `tutarin yaninda adres kisaltmasi varsa tutar yine okunur`() {
        // "m" birimi mesafe icin elenir ama "M. Kemal" bir adres; para isareti
        // gorunuyorsa birim elemesi uygulanmamali.
        assertEquals(185.0, amount("₺185 M. Kemal Mah. → Kadıköy")!!, 0.001)
    }

    @Test
    fun `tutarin yaninda yuzde varsa tutar yine okunur`() {
        assertEquals(185.0, amount("₺185 · %20 kampanya")!!, 0.001)
    }

    @Test
    fun `para birimi rakama yapisik yazilabilir`() {
        assertEquals(240.0, amount("240TL")!!, 0.001)
        assertEquals(240.0, amount("TL240")!!, 0.001)
        assertEquals(240.0, amount("240TRY")!!, 0.001)
    }

    @Test
    fun `tl ile biten kelime para birimi sanilmaz`() {
        assertNull(amount("atl 250"))
    }

    @Test
    fun `bolunmez bosluklu binlik ayirici`() {
        assertEquals(1250.75, amount("\u20BA1\u00A0250,75")!!, 0.001)
        assertEquals(1250000.5, amount("1\u00A0250\u00A0000,50 TL")!!, 0.001)
    }

    @Test
    fun `duz bosluklu binlik ayirici yalnizca kurus haneliyken birlesir`() {
        assertEquals(1250.75, amount("Ücret 1 250,75 TL")!!, 0.001)
        // Kurus hanesi yoksa iki ayri sayidir; birlestirilmemeli.
        assertEquals(185.0, amount("₺185 250 puan")!!, 0.001)
    }

    @Test
    fun `para birimi simge olarak ciziliyorsa kurus haneli yazim yeter`() {
        // Bazi uygulamalar TL isaretini yazi degil resim olarak cizer; ekranda
        // hic "₺" metni olmaz. Etiket hemen onunde ve kurus hanesi varsa tutardir.
        val candidate = AmountParser.bestAmount("Tahmini ücret: 342,50")
        assertEquals(342.50, candidate!!.value, 0.001)
        assertEquals(2, candidate.confidence)
    }

    @Test
    fun `kurus hanesi olmayan etiketli sayi dusuk guvende kalir`() {
        assertEquals(1, AmountParser.bestAmount("Tahmini ücret 190")!!.confidence)
    }

    @Test
    fun `etiketle sayi arasinda baska kelimeler varsa guven yukselmez`() {
        // Etiket sayinin hemen onunde degil; kurus hanesi tek basina yetmez.
        assertEquals(1, AmountParser.bestAmount("Ücret bilgisi aşağıda 342,50")!!.confidence)
    }

    @Test
    fun `cekim ekli ucret etiketleri de taninir`() {
        val candidate = AmountParser.bestAmount("Kazanacağınız tutar ₺275,00")
        assertEquals(275.0, candidate!!.value, 0.001)
        assertEquals(3, candidate.confidence)
    }

    @Test
    fun `ucret kelimesi baska bir kelimenin icindeyse sayilmaz`() {
        assertNull(amount("internet 250"))
    }

    @Test
    fun `etiketli sayinin yanindaki birim yine eler`() {
        assertNull(amount("Toplam 12,50 km"))
    }

    @Test
    fun `gercek cagri karti - aralikli tutar`() {
        // Ekran goruntusundeki kart: tutar aralik olarak yazili.
        // Alt sinir alinir; garanti edilen kazanc odur.
        val text = "Tümünü reddet (1) 8 dk · 2,81 km Köşklü Çeşme Mah., Gebze " +
            "4 dk · 1,45 km Mevlana Mah., Gebze Kabul et Toplam kazanç ₺200 - 250"
        val candidate = AmountParser.bestAmount(text)
        assertEquals(200.0, candidate!!.value, 0.001)
        assertEquals(3, candidate.confidence)
    }

    @Test
    fun `araligin ust ucu tutar sanilmaz`() {
        // "250" para isareti tasimiyor; tek basina aday olmamali.
        val values = AmountParser.candidates("Toplam kazanç ₺200 - 250").map { it.value }
        assertEquals(listOf(200.0), values)
    }

    @Test
    fun `adaylar guvene gore sirali gelir`() {
        val candidates = AmountParser.candidates("Ücret 100 · ₺250 · kazanç ₺400")
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.first().confidence >= candidates.last().confidence)
    }
}
