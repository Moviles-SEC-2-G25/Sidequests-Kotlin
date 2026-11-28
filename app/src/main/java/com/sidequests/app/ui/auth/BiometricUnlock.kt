package com.sidequests.app.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sidequests.app.ui.PrimaryButton

data class BiometricUi(
    val available: Boolean,
    val busy: Boolean,
    val error: String?,
    val authenticate: (userId: String, enabling: Boolean) -> Unit,
)

@Composable
fun rememberBiometricUi(viewModel: AuthViewModel): BiometricUi {
    val context = LocalContext.current
    val activity = remember(context) { context.fragmentActivity() }
    val manager = remember(context) { BiometricManager.from(context) }
    val authenticator = BiometricManager.Authenticators.BIOMETRIC_STRONG
    fun available() = activity != null && manager.canAuthenticate(authenticator) == BiometricManager.BIOMETRIC_SUCCESS
    var available by remember(manager, activity) { mutableStateOf(available()) }
    var owner by remember { mutableStateOf<String?>(null) }
    var enabling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { available = available() }

    val prompt = remember(activity, viewModel) {
        activity?.let {
            BiometricPrompt(it, ContextCompat.getMainExecutor(it), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val user = owner
                    owner = null
                    error = null
                    if (user != null) {
                        if (enabling) viewModel.setBiometricEnabled(user, true)
                        else viewModel.confirmBiometricUnlock(user)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    owner = null
                    error = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> null
                        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            "Biometric unlock is temporarily unavailable. Try again later or sign in with your account."
                        else -> "Biometric authentication failed. Check your device settings and try again."
                    }
                }
            })
        }
    }
    DisposableEffect(prompt) {
        onDispose { if (owner != null) prompt?.cancelAuthentication() }
    }

    return BiometricUi(available, owner != null, error) start@{ user, enable ->
        if (owner != null || prompt == null) return@start
        available = available()
        error = null
        if (!available) {
            error = "Set up biometrics in your device settings to continue."
            return@start
        }
        owner = user
        enabling = enable
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (enable) "Enable biometric unlock" else "Unlock Sidequests")
            .setAllowedAuthenticators(authenticator)
            .setNegativeButtonText("Cancel")
            .build())
    }
}

@Composable
fun BiometricLockScreen(state: AuthUiState, biometric: BiometricUi, onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Unlock Sidequests", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (biometric.available) "Confirm your identity to open your saved session."
            else "Biometrics are unavailable on this device. You can sign out and sign in again.",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            textAlign = TextAlign.Center,
        )
        (state.error ?: biometric.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PrimaryButton(
            "Unlock",
            { state.userId?.let { biometric.authenticate(it, false) } },
            enabled = !state.isBusy && !biometric.busy && biometric.available,
        )
        TextButton(onClick = onSignOut, enabled = !state.isBusy && !biometric.busy) { Text("Sign out") }
    }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}
