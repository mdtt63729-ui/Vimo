package com.atube.app.recommendation.shorts

import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.obj.StreamItem
import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.TopicAffinity
import com.atube.app.recommendation.models.UserInterestProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Multi-source Shorts candidate coordinator.
 *
 * Continuously discovers real Shorts by rotating through multiple search queries
 * and categories. When one query's pagination exhausts, it moves to the next query.
 *
 * Sources:
 * - Base shorts queries (shorts, viral shorts, funny shorts...)
 * - User-interest-based queries (creative shorts, gaming shorts...)
 * - Category-based queries (music shorts, tech shorts...)
 * - Trending (filtered for shorts)
 *
 * All results are deduplicated by video ID.
 */
class ShortsCandidateCoordinator {

    /** Base shorts queries — always available, India-first. */
    private val baseQueries = listOf(
        "shorts", "viral shorts", "funny shorts", "trending shorts",
        "comedy shorts", "dance shorts", "motivational shorts",
        "facts shorts", "cooking shorts", "life hack shorts",
        // India-first queries
        "indian shorts", "bollywood shorts", "cricket shorts",
        "hindi shorts", "tamil shorts", "telugu shorts",
        "desi shorts", "india viral shorts"
    )

    /** Interest-based queries — adapted to user's top interests. */
    private val interestQueries = listOf(
        "gaming shorts", "music shorts", "tech shorts", "ai shorts",
        "android shorts", "creative shorts", "design shorts",
        "editing shorts", "animation shorts", "art shorts",
        "cricket shorts", "bollywood shorts", "food shorts",
        "fitness shorts", "diy shorts",
        // India-interest queries
        "indian music shorts", "indian comedy shorts", "indian food shorts",
        "indian dance shorts", "bollywood dance shorts"
    )

    // State
    private var currentQueryIndex = 0
    private var currentNextPage: String? = null
    private val seenIds = mutableSetOf<String>()
    private val activeQueries = mutableListOf<String>()

    /**
     * Build the active query list based on user interests.
     * Interest-based queries are prioritized but base queries are always included.
     */
    private fun buildQueryList(profile: UserInterestProfile?): List<String> {
        if (profile == null || !profile.hasData) {
            return baseQueries + interestQueries
        }

        val topTopics = profile.topTopics(5).map { it.first }
        val interestBased = mutableListOf<String>()

        for (topic in topTopics) {
            // Map topic to shorts query
            val query = "${topic} shorts"
            if (query !in interestBased) {
                interestBased.add(query)
            }
        }

        // Interest queries first, then base queries as fallback
        return (interestBased + baseQueries).distinct()
    }

    /**
     * Fetch the next batch of Shorts.
     *
     * @param profile Optional user interest profile for personalization.
     * @return List of unique StreamItems (Shorts only).
     */
    suspend fun fetchNextBatch(profile: UserInterestProfile? = null): List<StreamItem> = withContext(Dispatchers.IO) {
        if (activeQueries.isEmpty()) {
            activeQueries.addAll(buildQueryList(profile))
        }

        val results = mutableListOf<StreamItem>()

        // Try current query with next page, or move to next query
        var attempts = 0
        while (results.size < 10 && attempts < activeQueries.size) {
            val query = activeQueries[currentQueryIndex % activeQueries.size]

            try {
                val searchResult = if (currentNextPage != null) {
                    MediaServiceRepository.instance.getSearchResultsNextPage(query, "videos", currentNextPage!!)
                } else {
                    MediaServiceRepository.instance.getSearchResults(query, "videos")
                }

                val shorts = searchResult.items
                    .filter { it.isShort == true || (it.duration != null && it.duration <= 60) }
                    .filter { it.url != null }
                    .map { it.toStreamItem() }
                    .filter { stream ->
                        val id = stream.url?.toID() ?: return@filter false
                        id !in seenIds
                    }

                // Mark as seen
                for (s in shorts) {
                    val id = s.url?.toID() ?: continue
                    seenIds.add(id)
                }

                results.addAll(shorts)
                currentNextPage = searchResult.nextpage

                // If this query is exhausted, move to next
                if (searchResult.nextpage == null) {
                    currentQueryIndex++
                    currentNextPage = null
                }

                if (results.size >= 10) break

            } catch (e: Exception) {
                // Query failed — move to next
                currentQueryIndex++
                currentNextPage = null
            }

            attempts++
        }

        results
    }

    /**
     * Fetch Shorts from trending (filtered for short-form content).
     * Runs concurrently with search-based fetching.
     */
    suspend fun fetchTrendingShorts(region: String): List<StreamItem> = withContext(Dispatchers.IO) {
        try {
            // Try multiple trending categories concurrently
            val results = supervisorScope {
                val categories = listOf(
                    com.atube.app.api.TrendingCategory.LIVE,
                    com.atube.app.api.TrendingCategory.GAMING,
                    com.atube.app.api.TrendingCategory.MUSIC
                )

                categories.map { category ->
                    async {
                        try {
                            MediaServiceRepository.instance.getTrending(region, category)
                                .filter { it.isShort }
                                .filter { stream ->
                                    val id = stream.url?.toID() ?: return@filter false
                                    id !in seenIds
                                }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }

            val allShorts = results.flatten().distinctBy { it.url?.toID() }

            // Mark as seen
            for (s in allShorts) {
                val id = s.url?.toID() ?: continue
                seenIds.add(id)
            }

            allShorts
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get a combined batch: trending shorts + search-based shorts.
     */
    suspend fun fetchCombinedBatch(
        region: String,
        profile: UserInterestProfile? = null
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val searchBatch = fetchNextBatch(profile)
        val trendingBatch = fetchTrendingShorts(region)

        // Merge, deduplicate, and shuffle slightly for variety
        val merged = (searchBatch + trendingBatch)
            .distinctBy { it.url?.toID() }
            .shuffled()
            .take(20)

        merged
    }

    /**
     * Reset state (e.g., on pull-to-refresh).
     */
    fun reset() {
        currentQueryIndex = 0
        currentNextPage = null
        seenIds.clear()
        activeQueries.clear()
    }

    /**
     * Check whether more Shorts can be loaded.
     */
    fun hasMore(): Boolean {
        return currentQueryIndex < activeQueries.size || currentNextPage != null
    }
}
