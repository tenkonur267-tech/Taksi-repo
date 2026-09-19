package com.taksi.autoaccept.overlay

/**
 * Baloncugun ekrandaki yerini hesaplayan saf mantik.
 *
 * Pencere islemlerinden ayri durur ki cihaz olmadan test edilebilsin:
 * ekran donunce ya da kucuk ekranli bir telefonda kayitli konum ekran
 * disinda kalirsa baloncuk erisilemez olur; asil risk budur.
 */
object OverlayPlacement {

    /** Kayitli konum yoksa: sag kenar, ekranin biraz alt yarisi. */
    fun default(screenWidth: Int, screenHeight: Int, size: Int, margin: Int): Pair<Int, Int> =
        (screenWidth - size - margin) to (screenHeight * 6 / 10)

    /** Konumu ekranin icinde tutar; kayitli konum artik sigmiyorsa geri ceker. */
    fun clamp(x: Int, y: Int, screenWidth: Int, screenHeight: Int, size: Int): Pair<Int, Int> {
        val maxX = (screenWidth - size).coerceAtLeast(0)
        val maxY = (screenHeight - size).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * Birakildigi yerden en yakin yan kenara yaslar. Baloncuk boylece
     * ekranin ortasinda kalip altindaki cagri kartini kapatmaz.
     */
    fun snapToEdge(x: Int, screenWidth: Int, size: Int, margin: Int): Int {
        val right = (screenWidth - size - margin).coerceAtLeast(0)
        val center = x + size / 2
        return if (center < screenWidth / 2) margin.coerceAtMost(right) else right
    }
}
