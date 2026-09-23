package com.taksi.autoaccept

import com.taksi.autoaccept.overlay.OverlayPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlacementTest {

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val size = 180
    private val margin = 18

    @Test
    fun `varsayilan konum sag kenarda ve ekranin icinde`() {
        val (x, y) = OverlayPlacement.default(screenWidth, screenHeight, size, margin)
        assertEquals(screenWidth - size - margin, x)
        assertTrue(y in 0..(screenHeight - size))
    }

    @Test
    fun `ekran disina tasan konum geri cekilir`() {
        val (x, y) = OverlayPlacement.clamp(5000, -300, screenWidth, screenHeight, size)
        assertEquals(screenWidth - size, x)
        assertEquals(0, y)
    }

    @Test
    fun `kucuk ekranda kayitli konum erisilebilir kalir`() {
        // Yatay moda donunce yukseklik kisalir; eski y degeri ekran disinda kalirdi.
        val (_, y) = OverlayPlacement.clamp(0, 2000, 2400, 1080, size)
        assertEquals(1080 - size, y)
    }

    @Test
    fun `sol yarida birakilan buton sol kenara yaslanir`() {
        assertEquals(margin, OverlayPlacement.snapToEdge(120, screenWidth, size, margin))
    }

    @Test
    fun `sag yarida birakilan buton sag kenara yaslanir`() {
        assertEquals(
            screenWidth - size - margin,
            OverlayPlacement.snapToEdge(700, screenWidth, size, margin)
        )
    }

    @Test
    fun `kenar boslugu birakilir`() {
        val (x, y) = OverlayPlacement.clamp(-500, -500, screenWidth, screenHeight, size, margin)
        assertEquals(margin, x)
        assertEquals(margin, y)
    }

    @Test
    fun `butondan dar ekranda pay feda edilir ama buton ekranda kalir`() {
        // Pay sigmiyorsa ortalanir; her durumda ekranin icinde.
        val (x, _) = OverlayPlacement.clamp(999, 0, 200, 2400, size, margin)
        assertTrue(x >= 0)
        assertTrue(x <= 200)
    }

    @Test
    fun `butondan dar ekranda yaslama negatif konum uretmez`() {
        val x = OverlayPlacement.snapToEdge(10, 100, size, margin)
        assertTrue(x >= 0)
    }
}
