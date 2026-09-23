package com.taksi.autoaccept.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taksi.autoaccept.core.log.LogRepository
import com.taksi.autoaccept.core.model.FilterSettings
import com.taksi.autoaccept.data.SettingsRepository
import com.taksi.autoaccept.util.InstalledApp
import com.taksi.autoaccept.util.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)

    val settings: StateFlow<FilterSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterSettings())

    val counters: StateFlow<SettingsRepository.Counters> = repository.counters
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.Counters(null, 0)
        )

    val logs = LogRepository.logs

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    fun loadApps() {
        if (_apps.value.isNotEmpty()) return
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                InstalledApps.launchable(getApplication())
            }
        }
    }

    fun update(transform: (FilterSettings) -> FilterSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun toggleTarget(packageName: String) = update { current ->
        val next = current.targetPackages.toMutableSet()
        if (!next.remove(packageName)) next.add(packageName)
        current.copy(targetPackages = next)
    }

    /** Baloncugun kayitli yerini siler; servis onu varsayilan koseye alir. */
    fun resetOverlayPosition() {
        viewModelScope.launch { repository.clearOverlayPosition() }
    }

    fun clearLogs() = LogRepository.clear()
}
