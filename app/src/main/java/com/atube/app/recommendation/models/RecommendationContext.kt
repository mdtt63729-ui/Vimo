package com.atube.app.recommendation.models

import com.atube.app.api.obj.StreamItem

/**
 * Context passed to candidate providers and the ranking engine.
 *
 * Contains user profile, session state, and configuration that
 * influence candidate generation and scoring.
 */
data class RecommendationContext(
    val sessionId: String,
    val category: String = "All",
    val region: String = "IN",
    val displayedIds: Set<String> = emptySet(),
    val dismissedVideoIds: Set<String> = emptySet(),
    val blockedChannelIds: Set<String> = emptySet(),
    val watchedVideoIds: Set<String> = emptySet(),
    val recentlyWatched: List<StreamItem> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val subscribedChannelIds: Set<String> = emptySet(),
    val weights: RecommendationWeights = RecommendationWeights.DEFAULT,
    val pageIndex: Int = 0,
    val explorationRatio: Float = 0.1f,
    val sessionSeed: Long = System.currentTimeMillis()
)

/**
 * The result of loading candidates from a provider.
 */
data class CandidateResult(
    val candidates: List<RecommendationCandidate>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null
)

/**
 * A page of candidates from the candidate coordinator.
 */
data class CandidatePage(
    val candidates: List<RecommendationCandidate>,
    val nextCursor: String? = null,
    val hasMore: Boolean = true
)
