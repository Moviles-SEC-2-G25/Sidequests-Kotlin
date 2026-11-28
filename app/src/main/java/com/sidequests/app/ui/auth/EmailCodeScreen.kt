package com.sidequests.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sidequests.app.ui.PrimaryButton
import kotlinx.coroutines.delay

@Composable
fun EmailCodeScreen(state: AuthUiState, isOnline: Boolean, viewModel: AuthViewModel) {
    val address = state.emailCodeAddress
    var email by remember { mutableStateOf(address.orEmpty()) }
    var code by remember(address) { mutableStateOf("") }
    var now by remember(state.resendAfterMillis) { mutableLongStateOf(authElapsedMillis()) }
    LaunchedEffect(state.resendAfterMillis) {
        while (now < state.resendAfterMillis) {
            delay(1_000)
            now = authElapsedMillis()
        }
    }
    val remaining = ((state.resendAfterMillis - now + 999) / 1_000).coerceAtLeast(0)
    val back = {
        if (address != null) viewModel.setEmailCodeMode(true) else viewModel.setEmailCodeMode(false)
    }
    BackHandler(onBack = back)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = back, enabled = !state.isBusy) { Text("Back") }
        Text(if (address == null) "Sign in with email code" else "Check your email",
            style = MaterialTheme.typography.headlineMedium)
        if (address == null) {
            Text("We'll send a six-digit code to the email on your Sidequests account.")
            AuthInput(
                value = email,
                onValueChange = {
                    email = it
                    viewModel.clearFeedback()
                },
                label = "Email",
                keyboardType = KeyboardType.Email,
                enabled = !state.isBusy,
            )
        } else {
            Text("Enter the six-digit code sent to $address.")
            AuthInput(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it in '0'..'9' }.take(EMAIL_CODE_LENGTH)
                    viewModel.clearFeedback()
                },
                label = "Sign-in code",
                keyboardType = KeyboardType.Number,
                enabled = !state.isBusy,
            )
        }
        if (!isOnline) {
            Text("Connect to the internet to ${if (address == null) "request" else "verify"} your sign-in code.",
                color = MaterialTheme.colorScheme.error)
        } else state.error?.takeIf { state.errorTitle != "You're offline" }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        PrimaryButton(
            if (address == null) "Send code" else "Verify code",
            onClick = {
                if (address == null) viewModel.sendEmailCode(email, isOnline)
                else viewModel.verifyEmailCode(code, isOnline)
            },
            enabled = isOnline && !state.isBusy && (address == null || code.length == EMAIL_CODE_LENGTH),
        )
        if (state.isBusy) CircularProgressIndicator()
        if (address != null) {
            TextButton(
                onClick = { viewModel.sendEmailCode(address, isOnline) },
                enabled = !state.isBusy && remaining == 0L && isOnline,
            ) { Text(if (remaining == 0L) "Resend code" else "Resend in ${remaining}s") }
            TextButton(onClick = back, enabled = !state.isBusy) { Text("Change email") }
        }
    }
}
