package com.sidequests.app.model

import com.sidequests.app.context.UserContext

data class QuestAlternative(
    val quest: Quest,
    val score: Int,
    val explanations: List<String>,
    val ruleCodes: List<String>,
)

data class QuestAlternativesState(
    val sourceQuestId: String,
    val sourceAttemptId: String,
    val reason: AbandonmentReason,
    val loading: Boolean = true,
    val suggestions: List<QuestAlternative> = emptyList(),
    val context: UserContext? = null,
    val simulatedContext: Boolean = false,
)
