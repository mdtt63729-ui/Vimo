package com.atube.app.recommendation.candidate

import com.atube.app.recommendation.models.CandidatePage
import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Coordinates concurrent fetching from multiple candidate providers.
 *
 * Uses [supervisorScope] so one provider failure does not cancel others.
 * All available providers run concurrently and their results are merged
 * into a single candidate pool with global deduplication.
 *
 * The coordinator maintains a large candidate pool and supports
 * continuous pagination through provider rotation.
 */
class CandidateCoordinator(
    private val providers: List<CandidateProvider> = listOf(
        SubscriptionCandidateProvider(),
        TrendingCandidateProvider(),
        RelatedCandidateProvider(),
        HistoryAffinityCandidateProvider(),
        SearchAffinityCandidateProvider(),
        FreshCandidateProvider(),
        CategoryCandidateProvider()
    )
) {
    /** Global deduplication set — tracks all video IDs ever fetched in this session. */
    private val globalSeenIds = mutableSetOf<String>()

    /** Candidate reserve — pre-fetched candidates not yet served. */
    private val candidateReserve = mutableListOf<RecommendationCandidate>()

    /** Whether any provider still has more content. */
    private var anyProviderHasMore = true

    /**
     * Fetch candidates from all available providers concurrently.
     *
     * @param context Recommendation context.
     * @param page Page index for pagination.
     * @return Merged [CandidatePage] with deduplicated candidates.
     */
    suspend fun fetchCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidatePage = withContext(Dispatchers.IO) {
        val availableProviders = providers.filter { it.isAvailable(context) }

        if (availableProviders.isEmpty()) {
            return@withContext CandidatePage(candidates = emptyList(), hasMore = false)
        }

        // Run all providers concurrently
        val results = supervisorScope {
            availableProviders.map { provider ->
                async {
                    try {
                        provider.getCandidates(context, page)
                    } catch (e: Exception) {
                        CandidateResult(candidates = emptyList(), hasMore = false)
                    }
                }
            }.awaitAll()
        }

        // Merge all candidates with global deduplication
        val mergedMap = mutableMapOf<String, RecommendationCandidate>()
        var hasMore = false

        for (result in results) {
            if (result.hasMore) hasMore = true

            for (candidate in result.candidates) {
                val id = candidate.videoId
                if (id.isBlank()) continue

                // Global dedup — skip if already fetched in any previous call
                if (id in globalSeenIds) continue
                globalSeenIds.add(id)

                val existing = mergedMap[id]
                if (existing == null) {
                    mergedMap[id] = candidate
                } else {
                    // Combine source signals — multi-source boost
                    val combinedSignals = existing.sourceSignals.copy(
                        isFromSubscription = existing.sourceSignals.isFromSubscription || candidate.sourceSignals.isFromSubscription,
                        isFromTrending = existing.sourceSignals.isFromTrending || candidate.sourceSignals.isFromTrending,
                        isRelated = existing.sourceSignals.isRelated || candidate.sourceSignals.isRelated,
                        sourceCount = existing.sourceSignals.sourceCount + 1
                    )
                    mergedMap[id] = existing.copy(sourceSignals = combinedSignals)
                }
            }
        }

        anyProviderHasMore = hasMore

        CandidatePage(
            candidates = mergedMap.values.toList(),
            hasMore = hasMore || candidateReserve.isNotEmpty()
        )
    }

    /**
     * Check whether more candidates can be fetched from any provider.
     */
    fun hasMoreCandidates(): Boolean = anyProviderHasMore

    /**
     * Get the total number of unique candidates fetched so far.
     */
    fun totalFetched(): Int = globalSeenIds.size

    /**
     * Reset state for a fresh session.
     */
    fun reset() {
        globalSeenIds.clear()
        candidateReserve.clear()
        anyProviderHasMore = true
    }
}
