package com.sidequests.app.ui

import android.content.Context
import com.sidequests.app.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sidequests.app.data.LocalQuestCatalogueStore
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.SidequestsRepository
import com.sidequests.app.data.SupabaseSidequestsRepository
import com.sidequests.app.data.local.LexSyncWorker
import com.sidequests.app.data.photo.AttemptEvidenceRepository
import com.sidequests.app.data.offline.OfflinePackManager
import com.sidequests.app.data.remote.SupabaseProvider
import com.sidequests.app.context.AddressProvider
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.UserContext
import com.sidequests.app.data.settings.SettingsStore
import com.sidequests.app.telemetry.FailureReporter
import java.io.File

class AppViewModelFactory(
    private val contextManager: ContextManager,
    private val applicationContext: Context,
    private val alternativeContextOverride: UserContext? = null,
    private val addressProvider: AddressProvider? = null,
    private val settingsStore: SettingsStore? = null,
    private val failureReporter: FailureReporter = FailureReporter.NONE,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AppViewModel(
            contextManager = contextManager,
            repository = repository,
            alternativeContextReader = { alternativeContextOverride ?: contextManager.readContext() },
            alternativeContextSimulated = alternativeContextOverride != null,
            localStore = localStore,
            attemptEvidenceRepository = AttemptEvidenceRepository(
                store = localStore,
                photoDirectory = File(applicationContext.filesDir, "quest-proofs"),
                client = if (SupabaseProvider.isConfigured) SupabaseProvider.client else null,
                bucket = BuildConfig.SUPABASE_QUEST_PROOFS_BUCKET,
            ),
            scheduleLocalSync = { userId -> LexSyncWorker.schedule(applicationContext, userId) },
            retryLocalSync = { userId -> LexSyncWorker.schedule(applicationContext, userId, retryNow = true) },
            addressProvider = addressProvider,
            settingsStore = settingsStore,
            failureReporter = failureReporter,
            offlinePackManager = offlinePackManager,
            missionHistorySource = if (SupabaseProvider.isConfigured) {
                com.sidequests.app.data.history.MissionHistoryRepository(
                    com.sidequests.app.data.history.MissionHistoryStore(
                        File(applicationContext.noBackupFilesDir, "mission_history"),
                    ),
                    com.sidequests.app.data.history.SupabaseHistoryRemote(SupabaseProvider.client),
                )
            } else null,
        ) as T
    }
}
