package com.taksi.autoaccept.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bellekte tutulan halka tampon. Kalibrasyon icin yeterli;
 * uygulama surecinin kapanmasiyla silinir, kalici depolama gerektirmez.
 */
object LogRepository {

    private const val CAPACITY = 200

    private val _logs = MutableStateFlow<List<DecisionLog>>(emptyList())
    val logs: StateFlow<List<DecisionLog>> = _logs.asStateFlow()

    fun add(log: DecisionLog) {
        _logs.value = (listOf(log) + _logs.value).take(CAPACITY)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
