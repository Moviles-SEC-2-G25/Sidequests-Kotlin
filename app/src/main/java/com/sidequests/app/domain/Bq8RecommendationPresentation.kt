package com.sidequests.app.domain

import com.sidequests.app.model.Quest

/**
 * BQ8 experiment: display the exact order supplied by the shared recommend_quests RPC.
 * Re-ranking this list on the phone would invalidate the server A/B experiment.
 * The local context ranking remains available only for the offline/sample fallback.
 */
internal object Bq8RecommendationPresentation {
    fun orderedQuests(serverQuestIds: List<String>, catalogue: List<Quest>): List<Quest> {
        if (serverQuestIds.isEmpty()) return emptyList()
        val byId = catalogue.associateBy { it.id }
        return serverQuestIds.mapNotNull(byId::get).distinctBy { it.id }
    }

    /** Only active server-side experiment arms are labelled; no missing variant is invented. */
    fun experimentArm(variants: List<String?>): String? {
        if (variants.isEmpty()) return null
        val unique = variants.toSet()
        return unique.singleOrNull()?.takeIf { it == "control" || it == "diverse" }
    }
}
