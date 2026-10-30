package com.sidequests.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sidequests.app.context.AddressProvider
import com.sidequests.app.context.ContextManager
import com.sidequests.app.context.UserContext

class AppViewModelFactory(
    private val contextManager: ContextManager,
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
            alternativeContextReader = { alternativeContextOverride ?: contextManager.readContext() },
            alternativeContextSimulated = alternativeContextOverride != null,
            addressProvider = addressProvider,
        ) as T
    }
}
