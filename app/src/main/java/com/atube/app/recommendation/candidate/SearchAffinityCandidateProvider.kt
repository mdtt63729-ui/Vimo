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
 * Provides candidates based on search history.
 *
 * Uses recent search queries to find related content. Does NOT blindly
 * show exact search results — instead searches with multiple queries
 * and uses pagination via nextPage tokens.
 *
 * The number of searches and pages scales with the page index to
 * provide continuous content.
 */
class SearchAffinityCandidateProvider : CandidateProvider {

    override val priority: Int = 8

    override suspend fun isAvailable(context: RecommendationContext): Boolean {
        return context.searchHistory.isNotEmpty()
    }

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        val searchHistory = context.searchHistory
        if (searchHistory.isEmpty()) {
            return CandidateResult(emptyList(), hasMore = false)
        }

        val candidates = mutableListOf<RecommendationCandidate>()
        val seenIds = mutableSetOf<String>()

        // Use more searches on higher pages — 2 on page 0, 3 on page 1, etc.
        val searchCount = minOf(2 + page, searchHistory.size)
        val searchesToUse = searchHistory.take(searchCount)

        for (query in searchesToUse) {
            try {
                // For page > 0, try to use nextPage from previous fetches
                val searchResult = MediaServiceRepository.instance
                    .getSearchResults(query, "all")

                val nextPage = searchResult.nextpage

                searchResult.items
                    .map { it.toStreamItem() }
                    .take(15)
                    .forEach { stream ->
                        val videoId = stream.url?.toID() ?: return@forEach
                        if (videoId in seenIds || videoId in context.displayedIds) {
                            return@forEach
                        }
                        if (stream.isShort) return@forEach

                        seenIds.add(videoId)
                        candidates.add(
                            RecommendationCandidate(
                                video = stream,
                                sourceSignals = SourceSignals(
                                    relatednessScore = 0.5f
                                ),
                                source = CandidateSource.SEARCH_AFFINITY
                            )
                        )
                    }

                // If we have a nextPage, fetch it too for more candidates
                if (nextPage != null && candidates.size < 20) {
                    try {
                        val nextPageResult = MediaServiceRepository.instance
                            .getSearchResultsNextPage(query, "all", nextPage)

                        nextPageResult.items
                            .map { it.toStreamItem() }
                            .filter { !it.isShort && it.url != null }
                            .take(10)
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
                                            relatednessScore = 0.4f
                                        ),
                                        source = CandidateSource.SEARCH_AFFINITY
                                    )
                                )
                            }
                    } catch (e: Exception) {
                        // nextPage failed — continue with what we have
                    }
                }
            } catch (e: Exception) {
                // Skip failed query
            }
        }

        // Has more if we have more search history to explore
        val hasMore = searchCount < searchHistory.size

        return CandidateResult(
            candidates = candidates,
            hasMore = hasMore
        )
    }
}
