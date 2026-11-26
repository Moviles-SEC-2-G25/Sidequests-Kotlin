package com.sidequests.app.ui

import com.sidequests.app.data.history.HistorySource
import com.sidequests.app.domain.HistoryFilter
import com.sidequests.app.domain.HistorySnapshot
import com.sidequests.app.domain.MissionHistory
import com.sidequests.app.domain.buildMissionHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MissionHistoryUiState(
    val history: MissionHistory = MissionHistory(),
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.All,
    val initialized: Boolean = false,
    val hasSnapshot: Boolean = false,
    val offline: Boolean = false,
    val fromCache: Boolean = false,
    val refreshing: Boolean = false,
    val reconnecting: Boolean = false,
    val updating: Boolean = false,
    val error: String? = null,
)

/** Screen-owned state. All disk/network work belongs to the repository; CPU work belongs to Default. */
class MissionHistoryController(
    private val accountId: String,
    private val source: HistorySource,
    private val scope: CoroutineScope,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val build: suspend (HistorySnapshot, HistoryFilter, String) -> MissionHistory =
        { data, filter, query -> buildMissionHistory(data, filter, query) },
) {
    private val _state = MutableStateFlow(MissionHistoryUiState())
    val state = _state.asStateFlow()
    private var snapshot: HistorySnapshot? = null
    private var online: Boolean? = null
    private var loadJob: Job? = null
    private var rebuildJob: Job? = null
    private var reconnectJob: Job? = null
    private var generation = 0L

    fun connectivityChanged(connected: Boolean) {
        val previous = online
        if (previous == connected) return
        online = connected
        reconnectJob?.cancel()
        _state.update { it.copy(offline = !connected, reconnecting = false) }
        if (previous == null) {
            refresh()
        } else if (!connected) {
            loadJob?.cancel()
            loadJob = null
            _state.update { it.copy(refreshing = false, fromCache = snapshot != null) }
            if (snapshot == null) refresh()
        } else {
            // Preserve the previous result while a transient network settles.
            _state.update { it.copy(reconnecting = true) }
            reconnectJob = scope.launch {
                delay(3_000)
                loadJob?.join()
                if (online == true) {
                    _state.update { it.copy(reconnecting = false) }
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val connected = online ?: return
        if (loadJob?.isActive == true) return
        reconnectJob?.cancel()
        _state.update { it.copy(refreshing = true, reconnecting = false, error = null) }
        loadJob = scope.launch {
            try {
                val result = source.load(accountId, connected)
                if (result.snapshot != null && result.snapshot.userId == accountId) {
                    snapshot = result.snapshot
                    _state.update { it.copy(fromCache = !result.fresh, refreshing = false) }
                    rebuild(false)
                } else if (snapshot != null) {
                    _state.update { it.copy(fromCache = true, refreshing = false) }
                } else {
                    _state.update {
                        it.copy(initialized = true, hasSnapshot = false, refreshing = false,
                            error = if (online == false)
                                "You are offline and no history is saved. Your mission history will load when the connection returns."
                            else "Could not load your mission history. Please try again.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(initialized = true, refreshing = false,
                    fromCache = snapshot != null,
                    error = if (snapshot == null) "Could not load your mission history." else null) }
            }
        }
    }

    fun setQuery(value: String) {
        if (_state.value.query == value) return
        _state.update { it.copy(query = value) }
        rebuild(true)
    }

    fun setFilter(value: HistoryFilter) {
        if (_state.value.filter == value) return
        _state.update { it.copy(filter = value) }
        rebuild(false)
    }

    private fun rebuild(debounce: Boolean) {
        val run = ++generation
        rebuildJob?.cancel()
        val data = snapshot ?: return
        val filter = _state.value.filter
        val query = _state.value.query
        _state.update { it.copy(updating = true) }
        rebuildJob = scope.launch {
            try {
                if (debounce) delay(300)
                val result = withContext(cpu) { build(data, filter, query) }
                if (run == generation) _state.update {
                    it.copy(history = result, initialized = true, hasSnapshot = true,
                        updating = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (run == generation) _state.update {
                    it.copy(initialized = true, updating = false, error = "Could not process your mission history.")
                }
            }
        }
    }

    fun close() {
        generation++
        loadJob?.cancel()
        reconnectJob?.cancel()
        rebuildJob?.cancel()
    }
}
