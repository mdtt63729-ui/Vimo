package com.atube.app.recommendation.candidate

import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.TrendingCategory
import com.atube.app.api.obj.StreamItem
import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.SourceSignals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Provides candidates from YouTube/NewPipe trending.
 *
 * Fetches multiple trending categories (Gaming, Music, Trailers, Podcasts, Live)
 * to build a larger candidate pool. Trending is especially important for
 * cold-start users with no history.
 *
 * Pagination: trending returns a single batch per category, but by rotating
 * through categories we can simulate pagination.
 */
class TrendingCandidateProvider : CandidateProvider {

    override val priority: Int = 10

    /** All trending categories to fetch. */
    private val allCategories = listOf(
        TrendingCategory.LIVE,
        TrendingCategory.GAMING,
        TrendingCategory.MUSIC,
        TrendingCategory.TRAILERS,
        TrendingCategory.PODCASTS
    )

    override suspend fun isAvailable(context: RecommendationContext): Boolean = true

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        return try {
            val region = context.region

            // For page 0: fetch ALL categories concurrently
            // For page 1+: no more trending (single batch per category)
            if (page > 0) {
                return CandidateResult(candidates = emptyList(), hasMore = false)
            }

            val results = supervisorScope {
                allCategories.map { category ->
                    async {
                        try {
                            MediaServiceRepository.instance.getTrending(region, category)
                                .filter { !it.isShort }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }

            // Merge all trending results
            val candidates = mutableListOf<RecommendationCandidate>()
            var rank = 0

            for (trendingList in results) {
                for (stream in trendingList) {
                    candidates.add(
                        RecommendationCandidate(
                            video = stream,
                            sourceSignals = SourceSignals(
                                isFromTrending = true,
                                trendingRank = rank
                            ),
                            source = CandidateSource.TRENDING
                        )
                    )
                    rank++
                }
            }

            CandidateResult(
                candidates = candidates,
                hasMore = false // Trending is a single batch
            )
        } catch (e: Exception) {
            CandidateResult(candidates = emptyList(), hasMore = false)
        }
    }
}
