package com.sidequests.app.data.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AuthRepository {
    val sessionStatus: StateFlow<SessionStatus>

    suspend fun signIn(email: String, password: String)

    suspend fun signUp(
        displayName: String,
        email: String,
        password: String,
    )

    suspend fun signOut()

    /** Authentication feature: changes the password of the signed-in user. */
    suspend fun updatePassword(newPassword: String)
}

class SupabaseAuthRepository(
    private val client: SupabaseClient,
) : AuthRepository {

    override val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    override suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    override suspend fun signUp(
        displayName: String,
        email: String,
        password: String,
    ) {
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("display_name", displayName.trim())
            }
        }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun updatePassword(newPassword: String) {
        client.auth.updateUser {
            password = newPassword
        }
    }
}
