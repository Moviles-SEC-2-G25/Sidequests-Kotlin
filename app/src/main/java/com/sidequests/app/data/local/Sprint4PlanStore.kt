package com.sidequests.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Device-private, account-scoped saved instant plan. Never stores credentials or personal location. */
interface Sprint4PlanStore {
    suspend fun read(userId: String): String?
    suspend fun write(userId: String, questId: String?)
}

class SharedPreferencesSprint4PlanStore(context: Context) : Sprint4PlanStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "sidequests_sprint4_plans", Context.MODE_PRIVATE
    )

    override suspend fun read(userId: String): String? = withContext(Dispatchers.IO) {
        preferences.getString(key(userId), null)
    }

    override suspend fun write(userId: String, questId: String?) = withContext(Dispatchers.IO) {
        val editor = preferences.edit()
        if (questId == null) editor.remove(key(userId))
        else editor.putString(key(userId), questId)
        check(editor.commit()) { "Could not save the selected plan locally." }
    }

    private fun key(userId: String): String {
        require(userId.isNotBlank()) { "An authenticated account is required." }
        // User-scoped key prevents another signed-in account from loading someone else's plan.
        return "saved_plan_$userId"
    }
}
