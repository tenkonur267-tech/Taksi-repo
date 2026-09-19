package com.taksi.autoaccept.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taksi.autoaccept.core.model.FilterSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "oto_kabul")

/** Ayarlarin ve gunluk sayaclarin kalici deposu. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val dryRun = booleanPreferencesKey("dry_run")
        val targets = stringSetPreferencesKey("target_packages")
        val minAmount = doublePreferencesKey("min_amount")
        val maxAmount = doublePreferencesKey("max_amount")
        val maxDistance = doublePreferencesKey("max_distance_km")
        val acceptLabels = stringPreferencesKey("accept_labels")
        val blocked = stringPreferencesKey("blocked_keywords")
        val required = stringPreferencesKey("required_keywords")
        val cooldown = intPreferencesKey("cooldown_seconds")
        val dailyLimit = intPreferencesKey("max_accepts_per_day")
        val hoursEnabled = booleanPreferencesKey("working_hours_enabled")
        val workStart = intPreferencesKey("work_start_minute")
        val workEnd = intPreferencesKey("work_end_minute")
        val handleNotifications = booleanPreferencesKey("handle_notifications")
        val vibrate = booleanPreferencesKey("vibrate_on_accept")
        val overlay = booleanPreferencesKey("overlay_enabled")
        val diagnostic = booleanPreferencesKey("diagnostic_mode")

        // Baloncugun ekrandaki yeri
        val overlayX = intPreferencesKey("overlay_x")
        val overlayY = intPreferencesKey("overlay_y")

        // Sayaclar
        val lastAcceptMs = longPreferencesKey("last_accept_ms")
        val acceptsToday = intPreferencesKey("accepts_today")
        val acceptsDayStamp = stringPreferencesKey("accepts_day_stamp")
    }

    val settings: Flow<FilterSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): FilterSettings {
        val defaults = FilterSettings()
        return FilterSettings(
            enabled = this[Keys.enabled] ?: defaults.enabled,
            dryRun = this[Keys.dryRun] ?: defaults.dryRun,
            targetPackages = this[Keys.targets] ?: defaults.targetPackages,
            minAmount = this[Keys.minAmount] ?: defaults.minAmount,
            maxAmount = this[Keys.maxAmount] ?: defaults.maxAmount,
            maxDistanceKm = this[Keys.maxDistance] ?: defaults.maxDistanceKm,
            // Kabul etiketleri bosaltilirsa servis kor kalir; varsayilana geri dus.
            acceptLabels = this[Keys.acceptLabels]?.csvToList()?.takeIf { it.isNotEmpty() }
                ?: defaults.acceptLabels,
            blockedKeywords = this[Keys.blocked]?.csvToList() ?: defaults.blockedKeywords,
            requiredKeywords = this[Keys.required]?.csvToList() ?: defaults.requiredKeywords,
            cooldownSeconds = this[Keys.cooldown] ?: defaults.cooldownSeconds,
            maxAcceptsPerDay = this[Keys.dailyLimit] ?: defaults.maxAcceptsPerDay,
            workingHoursEnabled = this[Keys.hoursEnabled] ?: defaults.workingHoursEnabled,
            workStartMinute = this[Keys.workStart] ?: defaults.workStartMinute,
            workEndMinute = this[Keys.workEnd] ?: defaults.workEndMinute,
            handleNotifications = this[Keys.handleNotifications] ?: defaults.handleNotifications,
            vibrateOnAccept = this[Keys.vibrate] ?: defaults.vibrateOnAccept,
            overlayEnabled = this[Keys.overlay] ?: defaults.overlayEnabled,
            diagnosticMode = this[Keys.diagnostic] ?: defaults.diagnosticMode
        )
    }

    suspend fun update(transform: (FilterSettings) -> FilterSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.enabled] = next.enabled
            prefs[Keys.dryRun] = next.dryRun
            prefs[Keys.targets] = next.targetPackages
            prefs[Keys.minAmount] = next.minAmount
            prefs[Keys.maxAmount] = next.maxAmount
            prefs[Keys.maxDistance] = next.maxDistanceKm
            prefs[Keys.acceptLabels] = next.acceptLabels.joinCsv()
            prefs[Keys.blocked] = next.blockedKeywords.joinCsv()
            prefs[Keys.required] = next.requiredKeywords.joinCsv()
            prefs[Keys.cooldown] = next.cooldownSeconds
            prefs[Keys.dailyLimit] = next.maxAcceptsPerDay
            prefs[Keys.hoursEnabled] = next.workingHoursEnabled
            prefs[Keys.workStart] = next.workStartMinute
            prefs[Keys.workEnd] = next.workEndMinute
            prefs[Keys.handleNotifications] = next.handleNotifications
            prefs[Keys.vibrate] = next.vibrateOnAccept
            prefs[Keys.overlay] = next.overlayEnabled
            prefs[Keys.diagnostic] = next.diagnosticMode
        }
    }

    /**
     * Baloncugun son birakildigi yer; kayit yoksa null doner ve baloncuk
     * varsayilan kosesine kurulur.
     */
    val overlayPosition: Flow<Pair<Int, Int>?> = context.dataStore.data.map { prefs ->
        val x = prefs[Keys.overlayX]
        val y = prefs[Keys.overlayY]
        if (x != null && y != null) x to y else null
    }

    suspend fun saveOverlayPosition(x: Int, y: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.overlayX] = x
            prefs[Keys.overlayY] = y
        }
    }

    /** Kabul sayaclari: bekleme suresi ve gunluk limit icin. */
    data class Counters(val lastAcceptMs: Long?, val acceptsToday: Int)

    val counters: Flow<Counters> = context.dataStore.data.map { prefs ->
        val today = todayStamp()
        val sameDay = prefs[Keys.acceptsDayStamp] == today
        Counters(
            lastAcceptMs = prefs[Keys.lastAcceptMs]?.takeIf { it > 0L },
            acceptsToday = if (sameDay) prefs[Keys.acceptsToday] ?: 0 else 0
        )
    }

    suspend fun recordAccept(atMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            val today = todayStamp()
            val sameDay = prefs[Keys.acceptsDayStamp] == today
            prefs[Keys.lastAcceptMs] = atMs
            prefs[Keys.acceptsToday] = if (sameDay) (prefs[Keys.acceptsToday] ?: 0) + 1 else 1
            prefs[Keys.acceptsDayStamp] = today
        }
    }

    companion object {
        /** Bos girdiler atilarak virgulle ayrilmis listeye/listeden donusum. */
        fun String.csvToList(): List<String> =
            split(",").map { it.trim() }.filter { it.isNotEmpty() }

        fun List<String>.joinCsv(): String = joinToString(",")

        fun todayStamp(): String {
            val cal = java.util.Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }
    }
}
