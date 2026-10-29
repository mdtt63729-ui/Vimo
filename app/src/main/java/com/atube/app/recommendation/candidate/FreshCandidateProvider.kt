package com.atube.app.recommendation.candidate

import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.obj.StreamItem
import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.SourceSignals

/**
 * Fresh discovery provider — finds content the user has NOT seen before.
 *
 * Uses a rotating set of general discovery queries to surface fresh content.
 * Critical for cold-start and exploration.
 *
 * Pagination: supports multiple pages by:
 * 1. Rotating through different queries
 * 2. Using nextPage tokens from each query
 *
 * India-first: includes India-specific discovery queries.
 */
class FreshCandidateProvider : CandidateProvider {

    override val priority: Int = 5

    private val discoveryQueries = listOf(
        "trending", "viral", "popular", "amazing", "incredible",
        "best of", "top 10", "how to", "tutorial", "guide",
        "review", "unboxing", "explained", "documentary",
        // India-first queries
        "india trending", "india viral", "bollywood", "cricket highlights",
        "indian music", "indian comedy", "desi viral"
    )

    /** Per-query next-page token cache for real pagination. */
    private val nextPageTokens = mutableMapOf<String, String?>()

    override suspend fun isAvailable(context: RecommendationContext): Boolean = true

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        val candidates = mutableListOf<RecommendationCandidate>()
        val seenIds = mutableSetOf<String>()

        // For page 0, use first 3 queries
        // For page 1+, rotate through queries + use nextPage tokens
        val queriesToTry = if (page == 0) {
            discoveryQueries.take(3)
        } else {
            val startIdx = (page * 2) % discoveryQueries.size
            listOf(
                discoveryQueries[startIdx],
                discoveryQueries[(startIdx + 1) % discoveryQueries.size]
            )
        }

        for (query in queriesToTry) {
            try {
                val nextPage = nextPageTokens[query]
                val result = if (nextPage != null) {
                    MediaServiceRepository.instance.getSearchResultsNextPage(query, "all", nextPage)
                } else {
                    MediaServiceRepository.instance.getSearchResults(query, "all")
                }

                // Save nextPage token for real pagination
                nextPageTokens[query] = result.nextpage

                result.items
                    .map { it.toStreamItem() }
                    .filter { !it.isShort && it.url != null }
                    .take(15)
                    .forEach { stream ->
                        val videoId = stream.url?.toID() ?: return@forEach
                        if (videoId in seenIds || videoId in context.displayedIds) {
                            return@forEach
                        }
                        seenIds.add(videoId)
                        candidates.add(
                            RecommendationCandidate(
                                video = stream,
                                sourceSignals = SourceSignals(
                                    relatednessScore = 0.2f // low — fresh discovery
                                ),
                                source = CandidateSource.FRESH
                            )
                        )
                    }
            } catch (e: Exception) {
                // Skip failed query
            }
        }

        // Has more if any query has a nextPage token or we have more queries to try
        val hasMore = nextPageTokens.values.any { it != null } ||
            (page + 1) * 2 < discoveryQueries.size + 10

        return CandidateResult(
            candidates = candidates,
            hasMore = hasMore
        )
    }
}
