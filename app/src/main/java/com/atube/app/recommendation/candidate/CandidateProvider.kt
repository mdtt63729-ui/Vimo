package com.atube.app.recommendation.candidate

import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.RecommendationContext

/**
 * Interface for candidate sources that feed the ranking engine.
 *
 * Each provider fetches candidates from a specific source
 * (trending, subscriptions, related, search history, etc.)
 * and returns them as [CandidateResult].
 *
 * Providers should:
 * - Run concurrently where possible
 * - Fail gracefully (one provider failure must not cancel others)
 * - Not perform main-thread work
 */
interface CandidateProvider {
    /**
     * Fetch candidates from this source.
     *
     * @param context The recommendation context with user profile, session, etc.
     * @param page Zero-based page index for pagination.
     * @return [CandidateResult] with candidates and pagination info.
     */
    suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult

    /** Whether this provider is available (e.g. has subscriptions, has history). */
    suspend fun isAvailable(context: RecommendationContext): Boolean

    /** Priority for this provider (higher = fetched first). */
    val priority: Int
        get() = 0
}
