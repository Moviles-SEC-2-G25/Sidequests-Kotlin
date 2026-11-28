package com.sidequests.app.ui.auth

import androidx.lifecycle.ViewModelStore
import com.sidequests.app.data.auth.AuthRepository
import com.sidequests.app.data.auth.BiometricLockStore
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthParityFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val repo = FakeAuthRepository()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun `email code stays bound to the requested address and offline actions do not call the repo`() = runTest(dispatcher) {
        var now = 5_000L
        val auth = AuthViewModel(repo, elapsedMillis = { now }).also { viewModels.put("auth", it) }
        advanceUntilIdle()
        auth.setEmailCodeMode(true)
        auth.sendEmailCode("alice@example.test", isOnline = false)
        advanceUntilIdle()
        assertTrue(repo.sent.isEmpty())
        assertNull(auth.uiState.value.emailCodeAddress)

        auth.sendEmailCode(" alice@example.test ", isOnline = true)
        auth.sendEmailCode("bob@example.test", isOnline = true)
        advanceUntilIdle()
        assertEquals(listOf("alice@example.test"), repo.sent)
        assertEquals(AuthStage.SignedOut, auth.uiState.value.stage)
        assertEquals(65_000L, auth.uiState.value.resendAfterMillis)
        auth.sendEmailCode("bob@example.test", isOnline = true)
        advanceUntilIdle()
        assertEquals(1, repo.sent.size)

        now += 60_000
        auth.sendEmailCode("bob@example.test", isOnline = true)
        advanceUntilIdle()
        assertEquals(listOf("alice@example.test", "alice@example.test"), repo.sent)
        auth.verifyEmailCode("123456", isOnline = false)
        auth.verifyEmailCode("abc123", isOnline = true)
        advanceUntilIdle()
        assertTrue(repo.verified.isEmpty())

        auth.verifyEmailCode("123456", isOnline = true)
        auth.verifyEmailCode("123456", isOnline = true)
        advanceUntilIdle()
        assertEquals(listOf("alice@example.test" to "123456"), repo.verified)
        assertEquals(AuthStage.SignedIn, auth.uiState.value.stage)
        assertFalse(auth.uiState.value.emailCodeMode)
    }

    @Test fun `restored session requires the matching biometric result and sign out clears enrollment`() = runTest(dispatcher) {
        val enabledUsers = mutableSetOf("alice")
        val biometrics = object : BiometricLockStore {
            override fun enabled(userId: String) = userId in enabledUsers
            override fun setEnabled(userId: String, enabled: Boolean) {
                if (enabled) enabledUsers.add(userId) else enabledUsers.remove(userId)
            }
        }
        repo.sessionStatus.value = session("alice")
        val auth = AuthViewModel(repo, biometrics).also { viewModels.put("auth", it) }
        advanceUntilIdle()
        assertEquals(AuthStage.BiometricLocked, auth.uiState.value.stage)
        auth.confirmBiometricUnlock("bob")
        assertEquals(AuthStage.BiometricLocked, auth.uiState.value.stage)
        auth.confirmBiometricUnlock("alice")
        assertEquals(AuthStage.SignedIn, auth.uiState.value.stage)

        repo.sessionStatus.value = session("bob")
        advanceUntilIdle()
        auth.setBiometricEnabled("alice", true)
        assertFalse(auth.uiState.value.biometricEnabled)
        auth.setBiometricEnabled("bob", true)
        assertTrue(auth.uiState.value.biometricEnabled)
        auth.signOut()
        advanceUntilIdle()
        assertEquals(AuthStage.SignedOut, auth.uiState.value.stage)
        assertFalse("bob" in enabledUsers)
    }

    private class FakeAuthRepository : AuthRepository {
        override val sessionStatus = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated())
        val sent = mutableListOf<String>()
        val verified = mutableListOf<Pair<String, String>>()

        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signUp(displayName: String, email: String, password: String) = Unit
        override suspend fun sendEmailCode(email: String) { sent += email }
        override suspend fun verifyEmailCode(email: String, code: String) {
            verified += email to code
            sessionStatus.value = session("alice")
        }
        override suspend fun signOut() { sessionStatus.value = SessionStatus.NotAuthenticated(isSignOut = true) }
        override suspend fun updatePassword(newPassword: String) = Unit
    }

    companion object {
        private fun session(user: String) = SessionStatus.Authenticated(
            UserSession("fixture-access", "fixture-refresh", expiresIn = 3_600L, tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = user, email = "$user@example.test")),
        )
    }
}
