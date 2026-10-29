package com.atube.app.recommendation.candidate

import com.atube.app.api.SubscriptionHelper
import com.atube.app.api.obj.StreamItem
import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.SourceSignals

/**
 * Provides candidates from the user's subscription feed.
 *
 * Subscribed channel videos receive a strong relevance signal
 * but must be mixed with discovery content — Home is NOT
 * a subscription-only feed.
 */
class SubscriptionCandidateProvider : CandidateProvider {

    override val priority: Int = 20

    override suspend fun isAvailable(context: RecommendationContext): Boolean {
        return context.subscribedChannelIds.isNotEmpty()
    }

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        if (!isAvailable(context)) {
            return CandidateResult(emptyList(), hasMore = false)
        }

        return try {
            val feed: List<StreamItem> = SubscriptionHelper.getFeed(forceRefresh = false)

            val candidates = feed
                .filter { !it.isShort } // Shorts go in the Shorts shelf
                .map { stream ->
                    RecommendationCandidate(
                        video = stream,
                        sourceSignals = SourceSignals(
                            isFromSubscription = true
                        ),
                        source = CandidateSource.SUBSCRIPTIONS
                    )
                }

            CandidateResult(
                candidates = candidates,
                hasMore = false // Feed is a single batch
            )
        } catch (e: Exception) {
            CandidateResult(candidates = emptyList(), hasMore = false)
        }
    }
}
