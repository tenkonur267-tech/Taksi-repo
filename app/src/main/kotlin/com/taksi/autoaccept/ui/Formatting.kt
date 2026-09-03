package com.taksi.autoaccept.ui

import java.util.Locale

private val TR: Locale = Locale.forLanguageTag("tr")

/** Sayiyi metin kutusunda gosterilecek hale getirir; 0 ise bos birakir. */
fun Double.toFieldText(): String = when {
    this <= 0.0 -> ""
    this % 1.0 == 0.0 -> toInt().toString()
    else -> String.format(TR, "%.2f", this)
}

/** Kullanicinin yazdigi tutari sayiya cevirir; bos veya hatali ise 0. */
fun String.toAmountOrZero(): Double =
    replace(" ", "").replace(",", ".").toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

fun Int.toFieldText(): String = if (this <= 0) "" else toString()

fun String.toIntOrZero(): Int = trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
