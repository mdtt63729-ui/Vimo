package com.atube.app.recommendation.candidate

import com.atube.app.api.obj.StreamItem
import com.atube.app.db.DatabaseHelper
import com.atube.app.db.DatabaseHolder
import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.SourceSignals

/**
 * Provides candidates based on watch history affinity.
 *
 * Extracts watched video IDs, channel IDs, and topic information
 * from watch history to generate affinity-based candidates.
 * Uses watch completion percentage to weight signals.
 */
class HistoryAffinityCandidateProvider : CandidateProvider {

    override val priority: Int = 12

    override suspend fun isAvailable(context: RecommendationContext): Boolean {
        return context.recentlyWatched.isNotEmpty()
    }

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        // This provider primarily provides signals rather than new candidates.
        // It fetches recent watch history items that can be used
        // to boost related content from other providers.
        // The actual candidate generation happens via RelatedCandidateProvider.
        //
        // However, we can also surface partially-watched videos
        // as "continue watching" candidates.

        return try {
            val watchHistory = DatabaseHelper.getWatchHistoryPage(1, 20)

            // Convert watch history to candidates with watch-completion signals
            val candidates = watchHistory.mapNotNull { historyItem ->
                val streamItem = historyItem.toStreamItem()
                val videoId = streamItem.url?.let {
                    if (it.startsWith("http")) it.substringAfter("v=").substringBefore("&")
                    else it
                } ?: return@mapNotNull null

                // Only include partially watched videos (10-95% completion)
                val watchPosition = DatabaseHelper.getWatchPosition(videoId)
                val duration = streamItem.duration ?: 0L
                val completionRatio = if (duration > 0) {
                    ((watchPosition ?: 0L).toFloat() / (duration * 1000).toFloat()).coerceIn(0f, 1f)
                } else 0f

                if (completionRatio in 0.1f..0.95f) {
                    RecommendationCandidate(
                        video = streamItem,
                        sourceSignals = SourceSignals(
                            isFromSubscription = false,
                            isFromTrending = false,
                            isRelated = false
                        ),
                        source = CandidateSource.HISTORY_AFFINITY
                    )
                } else null
            }

            CandidateResult(
                candidates = candidates,
                hasMore = false
            )
        } catch (e: Exception) {
            CandidateResult(candidates = emptyList(), hasMore = false)
        }
    }
}
