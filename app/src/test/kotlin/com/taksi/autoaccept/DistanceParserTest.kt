package com.taksi.autoaccept

import com.taksi.autoaccept.core.parse.DistanceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceParserTest {

    @Test
    fun `kartta ilk yazan mesafe yolcuya uzakliktir`() {
        // Ustte yolcuya gidis, altta yolculugun kendisi.
        val text = "8 dk · 2,81 km Köşklü Çeşme Mah., Gebze 4 dk · 1,45 km Mevlana Mah., Gebze"
        assertEquals(2.81, DistanceParser.pickupKm(text)!!, 0.001)
    }

    @Test
    fun `metre cinsinden yazim kilometreye cevrilir`() {
        assertEquals(0.75, DistanceParser.pickupKm("Yolcu 750 m uzakta")!!, 0.001)
    }

    @Test
    fun `tek mesafe varsa o alinir`() {
        assertEquals(7.5, DistanceParser.pickupKm("Yolcuya 7,5 km")!!, 0.001)
    }

    @Test
    fun `mesafe yoksa null doner`() {
        assertNull(DistanceParser.pickupKm("Yeni çağrı · Kabul Et"))
    }
}
