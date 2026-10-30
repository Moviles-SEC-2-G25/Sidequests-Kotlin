package com.sidequests.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidequests.app.data.auth.AuthRepository
import com.sidequests.app.data.auth.SupabaseAuthRepository
import com.sidequests.app.data.remote.SupabaseProvider
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStage {
    Initializing,
    SignedOut,
    SignedIn,
    ConfigurationMissing,
}

data class AuthUiState(
    val userId: String? = null,
    val stage: AuthStage = AuthStage.Initializing,
    val isBusy: Boolean = false,
    val userEmail: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class AuthViewModel(
    private val repository: AuthRepository? =
        if (SupabaseProvider.isConfigured) {
            SupabaseAuthRepository(SupabaseProvider.client)
        } else {
            null
        },
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        if (repository == null) {
            AuthUiState(stage = AuthStage.ConfigurationMissing)
        } else {
            AuthUiState()
        }
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        repository?.let { authRepository ->
            viewModelScope.launch {
                authRepository.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Initializing -> {
                            _uiState.update {
                                it.copy(
                                    stage = AuthStage.Initializing,
                                    isBusy = false,
                                )
                            }
                        }

                        is SessionStatus.Authenticated -> {
                            _uiState.update {
                                it.copy(
                                    stage = AuthStage.SignedIn,
                                    userId = status.session.user?.id,
                                    isBusy = false,
                                    userEmail = status.session.user?.email,
                                    message = null,
                                    error = null,
                                )
                            }
                        }

                        is SessionStatus.NotAuthenticated -> {
                            _uiState.update {
                                it.copy(
                                    stage = AuthStage.SignedOut,
                                    userId = null,
                                    isBusy = false,
                                    userEmail = null,
                                )
                            }
                        }

                        is SessionStatus.RefreshFailure -> {
                            _uiState.update {
                                it.copy(
                                    stage = AuthStage.SignedOut,
                                    isBusy = false,
                                    userEmail = null,
                                    error = "Your session could not be refreshed. Please sign in again.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun signIn(email: String, password: String) {
        val authRepository = repository ?: return

        if (!validCredentials(email, password)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, message = null, error = null)
            }

            runCatching {
                authRepository.signIn(email, password)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = throwable.message ?: "Unable to sign in.",
                    )
                }
            }
        }
    }

    fun signUp(
        displayName: String,
        email: String,
        password: String,
    ) {
        val authRepository = repository ?: return

        if (displayName.isBlank()) {
            _uiState.update { it.copy(error = "Enter your name.") }
            return
        }

        if (!validCredentials(email, password)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, message = null, error = null)
            }

            runCatching {
                authRepository.signUp(displayName, email, password)
            }.onSuccess {
                _uiState.update { current ->
                    if (current.stage == AuthStage.SignedIn) {
                        current.copy(isBusy = false)
                    } else {
                        current.copy(
                            isBusy = false,
                            message = "Account created. If email confirmation is enabled, confirm your email before signing in.",
                            error = null,
                        )
                    }
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = throwable.message ?: "Unable to create the account.",
                    )
                }
            }
        }
    }

    fun signOut() {
        val authRepository = repository ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }

            runCatching {
                authRepository.signOut()
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        error = throwable.message ?: "Unable to sign out.",
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    private fun validCredentials(email: String, password: String): Boolean {
        if (!email.contains("@") || email.isBlank()) {
            _uiState.update { it.copy(error = "Enter a valid email address.") }
            return false
        }

        if (password.length < 6) {
            _uiState.update {
                it.copy(error = "Password must contain at least 6 characters.")
            }
            return false
        }

        return true
    }
}
