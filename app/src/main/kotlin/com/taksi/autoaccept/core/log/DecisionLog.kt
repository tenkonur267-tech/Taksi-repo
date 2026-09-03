package com.taksi.autoaccept.core.log

import com.taksi.autoaccept.core.rules.Decision
import com.taksi.autoaccept.core.rules.RuleEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { ACCEPTED, SIMULATED, REJECTED, INFO, ERROR }

/** Kullaniciya gosterilen tek satirlik karar kaydi. */
data class DecisionLog(
    val timestampMs: Long,
    val level: LogLevel,
    val title: String,
    val detail: String,
    /** Kalibrasyon icin ekrandan okunan ham metin (kisaltilmis). */
    val rawText: String
) {
    val time: String
        get() = TIME_FORMAT.format(Date(timestampMs))

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("tr"))

        fun from(decision: Decision, rawText: String): DecisionLog {
            val now = System.currentTimeMillis()
            val short = rawText.take(400)
            return when (decision) {
                is Decision.Accept -> DecisionLog(
                    now, LogLevel.ACCEPTED,
                    "Kabul edildi · ${RuleEngine.format(decision.amount)} TL",
                    decision.distanceKm?.let { "${RuleEngine.format(it)} km" } ?: "",
                    short
                )

                is Decision.WouldAccept -> DecisionLog(
                    now, LogLevel.SIMULATED,
                    "Deneme: kabul edilirdi · ${RuleEngine.format(decision.amount)} TL",
                    decision.distanceKm?.let { "${RuleEngine.format(it)} km" } ?: "",
                    short
                )

                is Decision.Reject -> DecisionLog(
                    now, LogLevel.REJECTED,
                    "Atlandı · ${decision.reason.label}",
                    decision.detail,
                    short
                )

                Decision.Ignore -> DecisionLog(now, LogLevel.REJECTED, "Yok sayıldı", "", short)
            }
        }

        fun error(title: String, detail: String, rawText: String = "") =
            DecisionLog(System.currentTimeMillis(), LogLevel.ERROR, title, detail, rawText)

        fun info(title: String, detail: String = "") =
            DecisionLog(System.currentTimeMillis(), LogLevel.INFO, title, detail, "")
    }
}
