package com.sidequests.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sidequests.app.data.history.HistoryLoad
import com.sidequests.app.data.history.HistorySource
import com.sidequests.app.domain.HistoryEntry
import com.sidequests.app.domain.HistoryFilter
import com.sidequests.app.domain.HistoryMonth
import com.sidequests.app.domain.historyCategoryLabel
import com.sidequests.app.domain.normalizeHistorySearch
import com.sidequests.app.model.AppScreen
import com.sidequests.app.model.SidequestsUiState
import com.sidequests.app.ui.theme.DiscoveryTeal
import com.sidequests.app.ui.theme.ExplorerIndigo
import com.sidequests.app.ui.theme.QuestAmber
import java.text.NumberFormat
import java.util.Locale

private val monthNames = listOf("Enero","Febrero","Marzo","Abril","Mayo","Junio","Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre")
private val monthAbbreviations = listOf("Ene","Feb","Mar","Abr","May","Jun","Jul","Ago","Sep","Oct","Nov","Dic")
private fun thousands(n: Int) = NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO")).format(n)
private fun count(n: Int, one: String, many: String) = "${thousands(n)} ${if (n == 1) one else many}"
private val reasons = mapOf(
    "ran_out_of_time" to "⏰ Se me acabó el tiempo",
    "too_far" to "📍 Quedaba muy lejos",
    "too_expensive" to "💸 Era más costosa de lo esperado",
    "place_unavailable" to "🔒 El lugar estaba cerrado o no disponible",
    "too_difficult" to "😅 Era muy difícil para hoy",
    "weather_or_mood_changed" to "🌧 Cambió el clima o mi ánimo",
    "other" to "💬 Otra razón",
)

@Composable
fun MissionHistoryRoute(state: SidequestsUiState, viewModel: AppViewModel, online: Boolean) {
    val userId = state.accountUserId ?: return
    val scope = rememberCoroutineScope()
    val source = remember(viewModel) { viewModel.missionHistorySource ?: HistorySource { _, _ -> HistoryLoad(null,false) } }
    val controller = remember(userId, source, scope) { MissionHistoryController(userId, source, scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller, online) { controller.connectivityChanged(online) }
    val historyState by controller.state.collectAsStateWithLifecycle()
    if (state.screen == AppScreen.QuestDetail) {
        BackHandler { viewModel.navigate(AppScreen.MissionHistory) }
        QuestDetailScreen(viewModel.selectedQuest(), viewModel,
            onBack = { viewModel.navigate(AppScreen.MissionHistory) })
    } else {
        val back = { viewModel.navigate(AppScreen.Profile) }
        BackHandler(onBack = back)
        MissionHistoryScreen(historyState, controller::setQuery, controller::setFilter,
            controller::refresh, back, viewModel::openQuestFromHistory)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MissionHistoryScreen(
    state: MissionHistoryUiState,
    onQuery: (String) -> Unit,
    onFilter: (HistoryFilter) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Boolean,
) {
    var detailError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
            Text("Mi bitácora", style = MaterialTheme.typography.titleLarge)
        }
        when {
            !state.initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !state.hasSnapshot -> HistoryMessage(
                state.error ?: "No se pudo cargar tu bitácora.", "Reintentar", onRefresh)
            state.history.totalAttempts == 0 && !state.fromCache -> HistoryMessage(
                "Tu bitácora está vacía. ¡Acepta una misión en Explorar para empezarla!", "Volver", onBack)
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val notice = when {
                                state.offline -> "Sin conexión: estás viendo tu bitácora guardada. Se actualizará sola cuando vuelva el internet."
                                state.fromCache && (state.refreshing || state.reconnecting) -> "Conectado: actualizando tu bitácora…"
                                state.fromCache -> "No pudimos conectar con el servidor: estás viendo tu bitácora guardada. Desliza hacia abajo para reintentar."
                                else -> null
                            }
                            if (notice != null) Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp)) {
                                Text(notice + if (state.history.pendingSyncCount > 0)
                                    "\n" + count(state.history.pendingSyncCount,"misión con cambios por sincronizar.","misiones con cambios por sincronizar.") else "",
                                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(
                                value = state.query, onValueChange = onQuery,
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                placeholder = { Text("Buscar misión, lugar o categoría", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Text("⌕", fontSize = 24.sp) },
                                trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("✕") } },
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(HistoryFilter.All to "Todas", HistoryFilter.Completed to "Completadas",
                                    HistoryFilter.Abandoned to "Abandonadas").forEach { (filter,label) ->
                                    FilterChip(selected = state.filter == filter, onClick = { onFilter(filter) },
                                        label = { Text(label) }, shape = RoundedCornerShape(50),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                                }
                            }
                            val total = state.history.totalAttempts
                            val label = if (state.filter != HistoryFilter.All || normalizeHistorySearch(state.query).isNotEmpty())
                                "Mostrando ${state.history.matchCount} de ${count(total,"misión","misiones")}"
                                else count(total,"misión","misiones") + " en tu bitácora"
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            Box(Modifier.fillMaxWidth().height(2.dp)) {
                                if (state.updating || state.refreshing || state.reconnecting)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (detailError) Text("El detalle de esta misión no está disponible en el catálogo actual.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.history.months.isEmpty()) item(key = "no-matches") {
                        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⌕", fontSize = 32.sp)
                                Text(if (state.history.totalAttempts == 0) "No hay misiones en la copia guardada."
                                    else "Ninguna misión coincide", style = MaterialTheme.typography.titleSmall)
                                Text(if (state.history.totalAttempts == 0) "Se actualizará cuando vuelva la conexión."
                                    else "Prueba con otra palabra o cambia el filtro.", style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                    state.history.months.forEach { month ->
                        item(key = "month-${month.year}-${month.month}") { MissionMonthHeader(month) }
                        items(month.entries, key = { "attempt-${it.attempt.id}" }) { entry ->
                            MissionHistoryCard(entry) { detailError = !onOpen(entry.attempt.questId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HistoryMessage(message: String, button: String, action: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("📖", fontSize = 42.sp)
            Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(button, onClick = action)
        }
    }
}

@Composable private fun MissionMonthHeader(month: HistoryMonth) {
    val summary = buildList {
        if (month.completedCount > 0) add(count(month.completedCount,"completada","completadas"))
        if (month.abandonedCount > 0) add(count(month.abandonedCount,"abandonada","abandonadas"))
        if (month.inProgressCount > 0) add("${month.inProgressCount} en curso")
        if (month.completedMinutes > 0) add("${month.completedMinutes} min")
        month.steps?.let { add(count(it,"paso","pasos")) }
    }.joinToString(" · ")
    Column(Modifier.padding(top = 12.dp)) {
        Text("${monthNames[month.month-1]} ${month.year}", style = MaterialTheme.typography.titleMedium)
        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MissionHistoryCard(entry: HistoryEntry, onClick: () -> Unit) {
    val quest = entry.quest
    val attempt = entry.attempt
    val details = buildList {
        add("${monthAbbreviations[entry.date.monthValue-1]} ${entry.date.dayOfMonth}")
        quest?.let { add(historyCategoryLabel(it.category)); add("${it.durationMinutes} min") }
    }.joinToString(" · ")
    val (label, color) = when (attempt.status) {
        "completed" -> "Completada" to DiscoveryTeal
        "abandoned" -> "Abandonada" to Color(0xFFE05252)
        else -> "En curso" to MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = quest != null, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(quest?.emoji ?: "🎯", fontSize = 22.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(details, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    if (attempt.status == "completed") {
                        val rating = attempt.rating?.coerceIn(1,5)
                        if (rating != null) Text("★".repeat(rating) + "☆".repeat(5-rating), color = QuestAmber)
                        else Text("Sin calificar", style = MaterialTheme.typography.bodySmall)
                    } else if (attempt.status == "abandoned") {
                        reasons[attempt.abandonReason]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                entry.steps?.let { Text("🚶 " + count(it,"paso","pasos"), style = MaterialTheme.typography.bodySmall) }
                if (entry.pending) Text("☁ Pendiente de sincronizar", style = MaterialTheme.typography.bodySmall)
            }
            if (quest != null) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
        }
    }
}
