package com.sidequests.app.ui

/**
 * A recommendation acceptance is different from accepting a quest in Explore.
 *
 * BQ9 counts recommendation impressions and accepts only when the same quest
 * came from the Smart Picks list and a new quest attempt was started.
 */
internal fun isRecommendationAcceptance(
    questId: String,
    openedFromSmartPicksId: String?,
    currentRemoteRecommendations: List<String>,
    startsNewAttempt: Boolean,
): Boolean =
    startsNewAttempt &&
        openedFromSmartPicksId == questId &&
        questId in currentRemoteRecommendations
