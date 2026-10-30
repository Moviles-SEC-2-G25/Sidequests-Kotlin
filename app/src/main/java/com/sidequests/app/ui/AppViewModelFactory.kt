package com.sidequests.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.sidequests.app.data.LocalQuestCatalogueStore
import com.sidequests.app.data.SupabaseSidequestsRepository
import com.sidequests.app.data.InMemorySidequestsRepository
import com.sidequests.app.data.local.LexSyncWorker
import com.sidequests.app.data.remote.SupabaseProvider
import java.io.File
import androidx.lifecycle.ViewModelProvider
import com.sidequests.app.context.AddressProvider
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.UserContext

class AppViewModelFactory(
    private val contextManager: ContextManager,
    private val applicationContext: Context,
    private val alternativeContextOverride: UserContext? = null,
    private val addressProvider: AddressProvider? = null,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AppViewModel(
            contextManager = contextManager,
            repository = if (SupabaseProvider.isConfigured) {
                SupabaseSidequestsRepository(
                    SupabaseProvider.client,
                    LocalQuestCatalogueStore(File(applicationContext.filesDir, "quest_catalogue.json")),
                )
            } else InMemorySidequestsRepository(),
            alternativeContextReader = { alternativeContextOverride ?: contextManager.readContext() },
            alternativeContextSimulated = alternativeContextOverride != null,
            localStore = LexSyncWorker.store(applicationContext),
            scheduleLocalSync = { userId -> LexSyncWorker.schedule(applicationContext, userId) },
            retryLocalSync = { userId -> LexSyncWorker.schedule(applicationContext, userId, retryNow = true) },
            addressProvider = addressProvider,
        ) as T
    }
}
