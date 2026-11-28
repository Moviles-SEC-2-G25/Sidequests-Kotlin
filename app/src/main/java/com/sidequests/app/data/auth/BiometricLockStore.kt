package com.sidequests.app.data.auth

import android.content.Context
import androidx.core.content.edit

interface BiometricLockStore {
    fun enabled(userId: String): Boolean
    fun setEnabled(userId: String, enabled: Boolean)
}

class AndroidBiometricLockStore(context: Context) : BiometricLockStore {
    private val preferences = context.applicationContext.getSharedPreferences("biometric_lock", Context.MODE_PRIVATE)

    override fun enabled(userId: String): Boolean = preferences.getBoolean(userId, false)

    override fun setEnabled(userId: String, enabled: Boolean) {
        preferences.edit {
            if (enabled) putBoolean(userId, true) else remove(userId)
        }
    }
}
