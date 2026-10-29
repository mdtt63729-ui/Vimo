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
 * Category discovery provider — requests real content for specific categories.
 *
 * When a user selects "Gaming", this provider searches for gaming-related content
 * using real API queries. It does NOT just filter existing candidates — it fetches
 * NEW content from the network.
 *
 * Adjacent topics are used when the primary category runs low:
 *   Creative -> Design -> Editing -> Animation -> Art
 *
 * Pagination: uses nextPage tokens for real API pagination per query.
 *
 * India-first: includes India-specific category queries (cricket, bollywood, etc.)
 */
class CategoryCandidateProvider : CandidateProvider {

    override val priority: Int = 7

    /** Category -> search queries mapping with India-aware content. */
    private val categoryQueries: Map<String, List<String>> = mapOf(
        "Gaming" to listOf("gaming", "gameplay", "gaming highlights", "esports", "speedrun"),
        "Music" to listOf("music", "songs", "music video", "live music", "concert",
            "indian music", "bollywood songs"),
        "AI" to listOf("artificial intelligence", "ai", "chatgpt", "machine learning", "ai tools"),
        "Android" to listOf("android", "android app", "kotlin", "jetpack compose", "android development"),
        "Tech" to listOf("technology", "tech review", "gadgets", "programming", "software"),
        "News" to listOf("news", "breaking news", "world news", "current events",
            "india news", "indian news"),
        "Creative" to listOf("creative", "design", "editing", "animation", "art", "motion graphics"),
        "Sports" to listOf("sports", "cricket", "football", "highlights", "match",
            "ipl", "indian cricket"),
        "Entertainment" to listOf("entertainment", "movie", "trailer", "funny", "comedy",
            "bollywood", "tollywood"),
        "Live" to listOf("live", "live stream", "breaking", "24/7")
    )

    /** Adjacent topic fallback for when a category runs low. */
    private val adjacentTopics: Map<String, List<String>> = mapOf(
        "Creative" to listOf("Design", "Editing", "Animation", "Art"),
        "Android" to listOf("Tech", "Programming"),
        "Gaming" to listOf("Entertainment", "Tech"),
        "AI" to listOf("Tech", "Android"),
        "Music" to listOf("Entertainment", "Creative")
    )

    /** Per-query next-page token cache for real pagination. */
    private val nextPageTokens = mutableMapOf<String, String?>()

    override suspend fun isAvailable(context: RecommendationContext): Boolean {
        return context.category != "All" && context.category != "Explore"
    }

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        val category = context.category
        val queries = categoryQueries[category] ?: return CandidateResult(emptyList(), hasMore = false)

        val candidates = mutableListOf<RecommendationCandidate>()
        val seenIds = mutableSetOf<String>()

        // Use page index to rotate through queries
        val queryStartIdx = page * 2
        val queriesToTry = mutableListOf<String>()

        // Primary category queries
        for (i in 0 until minOf(2, queries.size)) {
            queriesToTry.add(queries[(queryStartIdx + i) % queries.size])
        }

        // If page > 0, also try adjacent topics
        if (page > 0) {
            val adjacent = adjacentTopics[category] ?: emptyList()
            for (adjCategory in adjacent.take(1)) {
                val adjQueries = categoryQueries[adjCategory] ?: continue
                queriesToTry.add(adjQueries[page % adjQueries.size])
            }
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
                                    relatednessScore = 0.4f
                                ),
                                source = CandidateSource.CATEGORY
                            )
                        )
                    }
            } catch (e: Exception) {
                // Skip failed query
            }
        }

        // Has more if any query has a nextPage token or we have more queries
        val hasMore = nextPageTokens.values.any { it != null } ||
            (queryStartIdx + 2) < queries.size + (adjacentTopics[category]?.size ?: 0) * 2

        return CandidateResult(
            candidates = candidates,
            hasMore = hasMore
        )
    }
}
