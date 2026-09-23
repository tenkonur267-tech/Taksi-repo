package com.taksi.autoaccept.core.rules

import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.core.model.RideRequest
import com.taksi.autoaccept.core.parse.DistanceParser
import java.util.Locale

/**
 * Bir cagriyi kabul edip etmeyecegimize karar verir.
 *
 * Tamamen saf fonksiyon: zaman ve sayac gibi disaridan gelen her sey
 * parametre olarak verilir, boylece birim testleriyle dogrulanabilir.
 */
class RuleEngine(private val settings: FilterSettings) {

    /** Kararin dayandigi sayac/zaman durumu. */
    data class Context(
        val nowMs: Long,
        val minuteOfDay: Int,
        val lastAcceptMs: Long?,
        val acceptsToday: Int
    )

    fun decide(request: RideRequest, context: Context): Decision {
        if (!settings.enabled) {
            return Decision.Reject(RejectReason.SERVICE_DISABLED)
        }
        if (request.sourcePackage !in settings.targetPackages) {
            return Decision.Reject(RejectReason.PACKAGE_NOT_WATCHED, request.sourcePackage)
        }
        if (request.fromNotification && !settings.handleNotifications) {
            return Decision.Ignore
        }

        val lower = request.flatText.lowercase(TR)

        // Yolcu cagrisina benzemiyorsa hic ugrasma; log gurultusu yapmayalim.
        if (!looksLikeRideRequest(lower)) {
            return Decision.Ignore
        }

        settings.blockedKeywords.firstOrNull { it.isNotBlank() && lower.contains(it.lowercase(TR)) }
            ?.let { return Decision.Reject(RejectReason.BLOCKED_KEYWORD, it) }

        val required = settings.requiredKeywords.filter { it.isNotBlank() }
        if (required.isNotEmpty() && required.none { lower.contains(it.lowercase(TR)) }) {
            return Decision.Reject(RejectReason.MISSING_REQUIRED_KEYWORD, required.joinToString(", "))
        }

        if (settings.workingHoursEnabled && !inWorkingHours(context.minuteOfDay)) {
            return Decision.Reject(RejectReason.OUTSIDE_HOURS, formatWindow())
        }

        if (settings.maxAcceptsPerDay > 0 && context.acceptsToday >= settings.maxAcceptsPerDay) {
            return Decision.Reject(RejectReason.DAILY_LIMIT, "${context.acceptsToday}/${settings.maxAcceptsPerDay}")
        }

        val last = context.lastAcceptMs
        if (last != null && settings.cooldownSeconds > 0) {
            val elapsed = (context.nowMs - last) / 1000
            if (elapsed < settings.cooldownSeconds) {
                return Decision.Reject(RejectReason.COOLDOWN, "${elapsed}sn/${settings.cooldownSeconds}sn")
            }
        }

        val candidate = request.amountCandidates.firstOrNull()
            ?: return Decision.Reject(RejectReason.NO_AMOUNT)

        // Sadece "ucret" kelimesine dayanan, para birimi gormedigimiz bir sayiyla
        // para harcamayiz; kullanici etiketleri duzeltene kadar reddederiz.
        if (candidate.confidence < 2) {
            return Decision.Reject(RejectReason.LOW_CONFIDENCE, candidate.raw)
        }

        val amount = candidate.value
        if (amount < settings.minAmount) {
            return Decision.Reject(RejectReason.BELOW_MIN, format(amount))
        }
        if (settings.maxAmount > 0.0 && amount > settings.maxAmount) {
            return Decision.Reject(RejectReason.ABOVE_MAX, format(amount))
        }

        val distanceKm = DistanceParser.nearestKm(request.flatText)
        if (settings.maxDistanceKm > 0.0 && distanceKm != null && distanceKm > settings.maxDistanceKm) {
            return Decision.Reject(RejectReason.TOO_FAR, "${format(distanceKm)} km")
        }

        return if (settings.dryRun) {
            Decision.WouldAccept(amount, distanceKm)
        } else {
            Decision.Accept(amount, distanceKm)
        }
    }

    /** Calisma penceresi gece yarisini asabilir: 22:00-06:00 gibi. */
    private fun inWorkingHours(minuteOfDay: Int): Boolean {
        val start = settings.workStartMinute
        val end = settings.workEndMinute
        return if (start <= end) {
            minuteOfDay in start..end
        } else {
            minuteOfDay >= start || minuteOfDay <= end
        }
    }

    private fun formatWindow(): String =
        "${hhmm(settings.workStartMinute)}-${hhmm(settings.workEndMinute)}"

    private fun looksLikeRideRequest(lower: String): Boolean {
        // Kabul dugmesi metni ya da bir para isareti gorunuyorsa cagri kartidir.
        val hasAcceptLabel = settings.acceptLabels.any { it.isNotBlank() && lower.contains(it.lowercase(TR)) }
        val hasMoney = lower.contains("₺") || MONEY_WORD.containsMatchIn(lower)
        return hasAcceptLabel || hasMoney
    }

    companion object {
        private val TR: Locale = Locale.forLanguageTag("tr")

        /**
         * "240TL" de bir para isaretidir; sozcuk siniri yerine harf kontrolu
         * kullaniyoruz ki rakama yapisik yazim da yakalansin, "atlı" gibi
         * kelimelerin icindeki "tl" yakalanmasin.
         */
        private val MONEY_WORD = Regex("""(?<![a-zçğıöşü])(?:tl|try|lira)(?![a-zçğıöşü])""")

        fun hhmm(minuteOfDay: Int): String {
            val m = ((minuteOfDay % 1440) + 1440) % 1440
            return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
        }

        fun format(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString()
            else String.format(Locale.forLanguageTag("tr"), "%.2f", value)
    }
}
