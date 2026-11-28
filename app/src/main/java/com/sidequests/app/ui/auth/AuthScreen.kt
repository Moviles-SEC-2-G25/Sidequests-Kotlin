package com.sidequests.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sidequests.app.ui.PrimaryButton
import com.sidequests.app.ui.theme.DiscoveryTeal
import com.sidequests.app.ui.theme.ExplorerIndigo

@Composable
fun AuthScreen(
    state: AuthUiState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onClearFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
    onEmailCode: (() -> Unit)? = null,
) {
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (createAccount) "Create your account" else "Sidequests",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(28.dp))

        if (createAccount) {
            AuthInput(
                value = displayName,
                onValueChange = {
                    displayName = it
                    onClearFeedback()
                },
                label = "Name",
                enabled = !state.isBusy,
            )

            Spacer(Modifier.height(12.dp))
        }

        AuthInput(
            value = email,
            onValueChange = {
                email = it
                onClearFeedback()
            },
            label = "Email",
            keyboardType = KeyboardType.Email,
            enabled = !state.isBusy,
        )

        Spacer(Modifier.height(12.dp))

        AuthInput(
            value = password,
            onValueChange = {
                password = it
                onClearFeedback()
            },
            label = "Password",
            keyboardType = KeyboardType.Password,
            enabled = !state.isBusy,
        )

        state.error?.let { error ->
            // The offline card already explains a missing connection.
            if (!isOnline && state.errorTitle == "You're offline") return@let
            Spacer(Modifier.height(16.dp))
            AuthNoticeCard(
                emoji = "⚠️",
                title = state.errorTitle ?: "Something went wrong",
                body = error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        state.message?.let { message ->
            Spacer(Modifier.height(16.dp))
            AuthNoticeCard(
                emoji = "✉️",
                title = "Almost there",
                body = message,
                containerColor = DiscoveryTeal.copy(alpha = .14f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(20.dp))

        PrimaryButton(
            text = if (createAccount) "Create account" else "Log in",
            onClick = {
                if (createAccount) {
                    onSignUp(displayName, email, password)
                } else {
                    onSignIn(email, password)
                }
            },
            enabled = !state.isBusy && isOnline,
        )

        if (state.isBusy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        if (!createAccount && onEmailCode != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onEmailCode,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Log in with a code") }
        }

        TextButton(
            onClick = {
                createAccount = !createAccount
                onClearFeedback()
            },
            enabled = !state.isBusy,
        ) {
            Text(
                if (createAccount) {
                    "Already have an account? Log in"
                } else {
                    "Don't have an account? Sign up"
                }
            )
        }
    }
}

@Composable
internal fun AuthInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = MaterialTheme.colorScheme
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation()
            else VisualTransformation.None,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.primaryContainer,
            unfocusedContainerColor = colors.primaryContainer,
            disabledContainerColor = colors.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Authentication feature: friendly card for sign-in errors, offline state and confirmations. */
@Composable
private fun AuthNoticeCard(
    emoji: String,
    title: String,
    body: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun MissingSupabaseConfigurationScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Backend configuration missing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Provide SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY through Gradle properties or environment variables, then rebuild the app.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
