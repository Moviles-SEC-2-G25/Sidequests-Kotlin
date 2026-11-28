package com.sidequests.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidequests.app.data.auth.AuthRepository
import com.sidequests.app.data.auth.BiometricLockStore
import com.sidequests.app.data.auth.SupabaseAuthRepository
import com.sidequests.app.data.remote.OFFLINE_ACTION_MESSAGE
import com.sidequests.app.data.remote.SupabaseProvider
import com.sidequests.app.data.remote.isNetworkFailure
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class AuthStage {
    Initializing,
    SignedOut,
    BiometricLocked,
    SignedIn,
    ConfigurationMissing,
}

data class AuthUiState(
    val userId: String? = null,
    val stage: AuthStage = AuthStage.Initializing,
    val isBusy: Boolean = false,
    val userEmail: String? = null,
    val userDisplayName: String? = null,
    val biometricEnabled: Boolean = false,
    val emailCodeMode: Boolean = false,
    val emailCodeAddress: String? = null,
    val resendAfterMillis: Long = 0L,
    val message: String? = null,
    val error: String? = null,
    /** Short headline shown above [error] in the sign-in error card. */
    val errorTitle: String? = null,
)

/** Authentication feature: state of the "Change password" dialog in Profile. */
data class PasswordChangeState(
    val isBusy: Boolean = false,
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
    private val biometricStore: BiometricLockStore? = null,
    private val elapsedMillis: () -> Long = ::authElapsedMillis,
) : ViewModel() {

    private var unlockedUser: String? = null
    private val resendDeadlines = mutableMapOf<String, Long>()

    private val _uiState = MutableStateFlow(
        if (repository == null) {
            AuthUiState(stage = AuthStage.ConfigurationMissing)
        } else {
            AuthUiState()
        }
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _passwordState = MutableStateFlow(PasswordChangeState())
    val passwordState: StateFlow<PasswordChangeState> = _passwordState.asStateFlow()

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
                            val userId = status.session.user?.id
                            if (status.isNew) unlockedUser = userId
                            resendDeadlines.clear()
                            val biometricEnabled = userId != null && biometricStore?.enabled(userId) == true
                            val requireUnlock = biometricEnabled && unlockedUser != userId
                            _uiState.update {
                                it.copy(
                                    stage = if (requireUnlock) AuthStage.BiometricLocked else AuthStage.SignedIn,
                                    userId = userId,
                                    isBusy = false,
                                    userEmail = status.session.user?.email,
                                    userDisplayName = (status.session.user?.userMetadata?.get("display_name") as? JsonPrimitive)
                                        ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
                                    biometricEnabled = biometricEnabled,
                                    emailCodeMode = false,
                                    emailCodeAddress = null,
                                    resendAfterMillis = 0L,
                                    message = null,
                                    error = null,
                                    errorTitle = null,
                                )
                            }
                        }

                        is SessionStatus.NotAuthenticated -> {
                            unlockedUser = null
                            _uiState.update {
                                it.copy(
                                    stage = AuthStage.SignedOut,
                                    userId = null,
                                    isBusy = false,
                                    userEmail = null,
                                    userDisplayName = null,
                                    biometricEnabled = false,
                                )
                            }
                        }

                        is SessionStatus.RefreshFailure -> {
                            unlockedUser = null
                            _uiState.update {
                                it.copy(
                                    stage = AuthStage.SignedOut,
                                    isBusy = false,
                                    userEmail = null,
                                    userDisplayName = null,
                                    userId = null,
                                    biometricEnabled = false,
                                    errorTitle = "Session expired",
                                    error = "Your session could not be refreshed. Please sign in again.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun signIn(email: String, password: String, isOnline: Boolean = true) {
        val authRepository = repository ?: return

        if (!validCredentials(email, password)) return
        if (!isOnline) {
            showError("You're offline", "Connect to the internet to sign in. Your saved quests will be waiting for you.")
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, message = null, error = null, errorTitle = null)
            }

            runCatching {
                authRepository.signIn(email, password)
            }.onFailure { throwable ->
                val (title, body) = describeAuthFailure(throwable, signUp = false)
                _uiState.update { it.copy(isBusy = false, errorTitle = title, error = body) }
            }
        }
    }

    fun setEmailCodeMode(enabled: Boolean) {
        val state = _uiState.value
        if (state.stage != AuthStage.SignedOut || state.isBusy) return
        _uiState.update {
            it.copy(emailCodeMode = enabled, emailCodeAddress = null, resendAfterMillis = 0L,
                message = null, error = null, errorTitle = null)
        }
    }

    fun sendEmailCode(email: String, isOnline: Boolean) {
        val authRepository = repository ?: return
        val state = _uiState.value
        if (state.stage != AuthStage.SignedOut || !state.emailCodeMode || state.isBusy) return
        val address = state.emailCodeAddress ?: email.trim()
        if (!EMAIL_ADDRESS.matches(address)) {
            showError("Check your email", "Enter a valid email address.")
            return
        }
        if (!isOnline) {
            showError("You're offline", "Connect to the internet to request a sign-in code.")
            return
        }
        val key = address.lowercase()
        val deadline = resendDeadlines[key]
        if (deadline != null && elapsedMillis() < deadline) {
            _uiState.update { it.copy(emailCodeAddress = address, resendAfterMillis = deadline) }
            return
        }
        startEmailCodeOperation("Couldn't send a code", authAction = {
            authRepository.sendEmailCode(address)
            val resendAfter = elapsedMillis() + 60_000L
            resendDeadlines[key] = resendAfter
            _uiState.update { it.copy(emailCodeAddress = address, resendAfterMillis = resendAfter) }
        })
    }

    fun verifyEmailCode(code: String, isOnline: Boolean) {
        val authRepository = repository ?: return
        val state = _uiState.value
        if (state.stage != AuthStage.SignedOut || !state.emailCodeMode || state.isBusy) return
        val address = state.emailCodeAddress ?: return
        if (code.length != EMAIL_CODE_LENGTH || code.any { it !in '0'..'9' }) {
            showError("Check the code", "Enter the six digits from your email.")
            return
        }
        if (!isOnline) {
            showError("You're offline", "Connect to the internet to verify your code.")
            return
        }
        startEmailCodeOperation("Couldn't verify the code", verifying = true, authAction = {
            authRepository.verifyEmailCode(address, code)
        })
    }

    private fun startEmailCodeOperation(title: String, verifying: Boolean = false, authAction: suspend () -> Unit) {
        _uiState.update { it.copy(isBusy = true, message = null, error = null, errorTitle = null) }
        viewModelScope.launch {
            try {
                authAction()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val body = when {
                    error.isNetworkFailure() -> "Check your connection and try again."
                    (error as? AuthRestException)?.errorCode in setOf(
                        AuthErrorCode.OverRequestRateLimit, AuthErrorCode.OverEmailSendRateLimit,
                    ) -> "Wait a minute before trying again."
                    verifying -> "Check the code or request a new one if it has expired."
                    else -> "Make sure you have an account with this email, then try again."
                }
                showError(title, body)
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun signUp(
        displayName: String,
        email: String,
        password: String,
        isOnline: Boolean = true,
    ) {
        val authRepository = repository ?: return

        if (displayName.isBlank()) {
            showError("Check your details", "Enter your name.")
            return
        }

        if (!validCredentials(email, password)) return
        if (!isOnline) {
            showError("You're offline", "Connect to the internet to create your account.")
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, message = null, error = null, errorTitle = null)
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
                            errorTitle = null,
                        )
                    }
                }
            }.onFailure { throwable ->
                val (title, body) = describeAuthFailure(throwable, signUp = true)
                _uiState.update { it.copy(isBusy = false, errorTitle = title, error = body) }
            }
        }
    }

    fun signOut() {
        val authRepository = repository ?: return
        val user = _uiState.value.userId

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }

            runCatching {
                authRepository.signOut()
                user?.let { biometricStore?.setEnabled(it, false) }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorTitle = "Couldn't sign out",
                        error = if (throwable.isNetworkFailure()) OFFLINE_ACTION_MESSAGE else "Please try again.",
                    )
                }
            }
        }
    }

    fun confirmBiometricUnlock(expectedUser: String) {
        val state = _uiState.value
        if (state.stage != AuthStage.BiometricLocked || state.userId != expectedUser || state.isBusy) return
        unlockedUser = expectedUser
        _uiState.update { it.copy(stage = AuthStage.SignedIn, error = null, errorTitle = null) }
    }

    fun setBiometricEnabled(expectedUser: String, enabled: Boolean) {
        val state = _uiState.value
        if (state.stage != AuthStage.SignedIn || state.userId != expectedUser || state.isBusy) return
        val store = biometricStore ?: return
        store.setEnabled(expectedUser, enabled)
        unlockedUser = expectedUser.takeIf { enabled }
        _uiState.update { it.copy(biometricEnabled = enabled) }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(message = null, error = null, errorTitle = null) }
    }

    /**
     * Authentication feature: changes the password from Profile.
     * Eventual connectivity: the request needs the auth server, so it is blocked with a clear
     * message while offline instead of failing with a generic network error.
     */
    fun changePassword(newPassword: String, confirmation: String, isOnline: Boolean) {
        val authRepository = repository ?: return
        val validationError = when {
            newPassword.length < 6 -> "Password must contain at least 6 characters."
            newPassword != confirmation -> "Passwords do not match."
            !isOnline -> "You're offline. Connect to the internet to change your password."
            else -> null
        }
        if (validationError != null) {
            _passwordState.value = PasswordChangeState(error = validationError)
            return
        }

        viewModelScope.launch {
            _passwordState.value = PasswordChangeState(isBusy = true)
            runCatching { authRepository.updatePassword(newPassword) }
                .onSuccess {
                    _passwordState.value = PasswordChangeState(message = "Password updated.")
                }
                .onFailure { throwable ->
                    _passwordState.value = PasswordChangeState(
                        error = when {
                            throwable.isNetworkFailure() -> OFFLINE_ACTION_MESSAGE
                            throwable is AuthWeakPasswordException -> "Choose a stronger password."
                            (throwable as? AuthRestException)?.errorCode == AuthErrorCode.SamePassword ->
                                "The new password must be different from the current one."
                            else -> "Unable to update the password. Please try again."
                        },
                    )
                }
        }
    }

    fun clearPasswordFeedback() {
        _passwordState.value = PasswordChangeState()
    }

    private fun validCredentials(email: String, password: String): Boolean {
        if (!email.contains("@") || email.isBlank()) {
            showError("Check your details", "Enter a valid email address.")
            return false
        }

        if (password.length < 6) {
            showError("Check your details", "Password must contain at least 6 characters.")
            return false
        }

        return true
    }

    private fun showError(title: String, body: String) {
        _uiState.update { it.copy(message = null, errorTitle = title, error = body) }
    }
}

internal const val EMAIL_CODE_LENGTH = 6
private val EMAIL_ADDRESS = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")
internal fun authElapsedMillis(): Long = System.nanoTime() / 1_000_000L

/**
 * Authentication feature: turns Supabase/network failures into a short, friendly title and body.
 * Raw exception messages include request URLs and headers, so they are never shown.
 */
internal fun describeAuthFailure(throwable: Throwable, signUp: Boolean): Pair<String, String> {
    if (throwable.isNetworkFailure()) {
        return "No connection" to "We couldn't reach Sidequests. Check your internet connection and try again."
    }
    if (throwable is AuthWeakPasswordException) {
        return "Choose a stronger password" to "Use at least 6 characters, mixing letters and numbers."
    }
    return when ((throwable as? AuthRestException)?.errorCode) {
        AuthErrorCode.InvalidCredentials ->
            "Couldn't sign you in" to "The email or password is incorrect. Check them and try again."
        AuthErrorCode.EmailNotConfirmed ->
            "Confirm your email" to "Open the confirmation link we sent to your email, then sign in."
        AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists ->
            "Account already exists" to "An account with this email already exists. Sign in instead."
        AuthErrorCode.EmailAddressInvalid, AuthErrorCode.ValidationFailed ->
            "Check your details" to "Enter a valid email address."
        AuthErrorCode.WeakPassword ->
            "Choose a stronger password" to "Use at least 6 characters, mixing letters and numbers."
        AuthErrorCode.OverRequestRateLimit, AuthErrorCode.OverEmailSendRateLimit ->
            "Too many attempts" to "Wait a minute before trying again."
        AuthErrorCode.SignupDisabled ->
            "Sign-up unavailable" to "New accounts can't be created right now."
        else -> if (signUp) {
            "Couldn't create your account" to "Something went wrong. Please try again in a moment."
        } else {
            "Couldn't sign you in" to "Something went wrong. Please try again in a moment."
        }
    }
}
