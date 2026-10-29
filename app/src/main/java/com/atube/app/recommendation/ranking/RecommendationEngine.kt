package com.atube.app.recommendation.ranking

import com.atube.app.api.obj.StreamItem
import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.RecommendationWeights
import com.atube.app.recommendation.models.UserInterestProfile
import kotlin.math.log10
import kotlin.random.Random

/**
 * The recommendation ranking engine.
 *
 * Calculates a composite score for each candidate based on:
 * - freshness (upload recency)
 * - popularity (log-normalized views)
 * - subscription affinity
 * - channel affinity (from user profile)
 * - topic affinity (from user profile)
 * - search affinity (search history tokens)
 * - relatedness (from related videos)
 * - trending signal
 * - novelty (new channel/topic)
 * - completion affinity (watch completion signals)
 * - category match
 * - multi-source boost
 * - watched penalty
 * - repeated channel penalty
 * - dismissed penalty
 * - stale penalty
 * - exploration randomness
 *
 * Phase 1 uses weighted heuristic ranking (no ML required).
 */
class RecommendationEngine {

    /**
     * Rank candidates by score, returning sorted list (highest first).
     *
     * @param candidates Raw candidates from the candidate coordinator.
     * @param context Recommendation context with user profile and session.
     * @param profile User interest profile for personalization.
     * @return Scored and sorted candidates.
     */
    fun rank(
        candidates: List<RecommendationCandidate>,
        context: RecommendationContext,
        profile: UserInterestProfile
    ): List<RecommendationCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val weights = context.weights
        val scored = candidates.map { candidate ->
            val score = calculateScore(candidate, context, profile, weights)
            candidate to score
        }

        // Sort by score descending
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    private fun calculateScore(
        candidate: RecommendationCandidate,
        context: RecommendationContext,
        profile: UserInterestProfile,
        weights: RecommendationWeights
    ): Double {
        val video = candidate.video
        val signals = candidate.sourceSignals
        val w = weights

        // === Positive signals ===

        // Freshness: decays with age
        val freshness = calculateFreshness(video.uploaded)
        val freshnessScore = freshness * w.freshness

        // Popularity: log-normalized views
        val popularity = calculatePopularity(video.views ?: 0L)
        val popularityScore = popularity * w.popularity

        // Subscription affinity
        val subscriptionScore = if (signals.isFromSubscription) w.subscription else 0.0

        // Channel affinity from user profile
        val channelId = extractChannelId(video)
        val channelAffinity = if (channelId != null) profile.channelScore(channelId).toDouble() else 0.0
        val channelAffinityScore = channelAffinity * w.channelAffinity

        // Topic affinity from user profile
        val topicScore = calculateTopicAffinity(video, profile)
        val topicAffinityScore = topicScore * w.topicAffinity

        // Search affinity
        val searchAffinity = calculateSearchAffinity(video, context.searchHistory)
        val searchAffinityScore = searchAffinity * w.searchAffinity

        // Relatedness
        val relatednessScore = signals.relatednessScore.toDouble() * w.relatedness

        // Trending signal
        val trendingScore = if (signals.isFromTrending) {
            val rankBonus = if (signals.trendingRank >= 0) {
                1.0 - (signals.trendingRank.toDouble() / 50.0).coerceAtMost(0.5)
            } else 0.5
            rankBonus * w.trending
        } else 0.0

        // Novelty — boost if channel is new to the user
        val isNovelChannel = channelId != null && channelId !in profile.channelScores
        val noveltyScore = if (isNovelChannel) w.novelty else 0.0

        // Multi-source boost
        val multiSourceScore = signals.multiSourceBoost.toDouble() * w.multiSourceBoost

        // Category match
        val categoryMatch = if (context.category != "All" && context.category != "Explore") {
            calculateCategoryMatch(video, context.category)
        } else 0.0
        val categoryMatchScore = categoryMatch * w.categoryMatch

        // === Negative signals (penalties) ===

        // Watched penalty — video already watched
        val videoId = candidate.videoId
        val watchedPenalty = if (videoId in context.watchedVideoIds) w.watchedPenalty else 0.0

        // Dismissed penalty
        val dismissedPenalty = if (videoId in context.dismissedVideoIds) w.dismissedPenalty else 0.0

        // Repeated channel penalty (checked by caller via diversity engine)
        // Stale penalty — very old content
        val stalePenalty = if (video.uploaded > 0) {
            val ageDays = (System.currentTimeMillis() - video.uploaded) / (1000.0 * 60 * 60 * 24)
            if (ageDays > 365) w.stalePenalty else 0.0
        } else 0.0

        // === Exploration: small random factor to prevent identical ordering ===
        val exploration = Random(context.sessionSeed + videoId.hashCode().toLong())
            .nextDouble() * w.explorationFactor

        // === Final score ===
        return freshnessScore +
            popularityScore +
            subscriptionScore +
            channelAffinityScore +
            topicAffinityScore +
            searchAffinityScore +
            relatednessScore +
            trendingScore +
            noveltyScore +
            multiSourceScore +
            categoryMatchScore +
            exploration -
            watchedPenalty -
            dismissedPenalty -
            stalePenalty
    }

    /**
     * Freshness score: decays gradually with age.
     * freshness = 1 / (1 + ageHours / decayConstant)
     */
    private fun calculateFreshness(uploadedMillis: Long): Double {
        if (uploadedMillis <= 0) return 0.0
        val ageHours = (System.currentTimeMillis() - uploadedMillis) / (1000.0 * 60 * 60)
        if (ageHours < 0) return 0.0 // Future upload
        val decayConstant = 72.0 // 3 days half-life
        return 1.0 / (1.0 + ageHours / decayConstant)
    }

    /**
     * Popularity score: log-normalized views.
     * popularity = log(views + 1) / log(referenceViews + 1)
     */
    private fun calculatePopularity(views: Long): Double {
        if (views <= 0) return 0.0
        val referenceViews = 1_000_000L // 1M views = max normalization
        return log10(views.toDouble() + 1) / log10(referenceViews.toDouble() + 1)
    }

    /**
     * Topic affinity: extract topics from title and match against profile.
     */
    private fun calculateTopicAffinity(video: StreamItem, profile: UserInterestProfile): Double {
        val title = video.title?.lowercase() ?: return 0.0
        val description = video.shortDescription?.lowercase() ?: ""
        val channelName = video.uploaderName?.lowercase() ?: ""

        var maxScore = 0.0
        for ((topic, _) in profile.topTopics(10)) {
            val topicLower = topic.lowercase()
            if (title.contains(topicLower) || description.contains(topicLower) || channelName.contains(topicLower)) {
                maxScore = maxOf(maxScore, profile.topicScore(topic).toDouble())
            }
        }
        return maxScore
    }

    /**
     * Search affinity: match video against search history tokens.
     */
    private fun calculateSearchAffinity(video: StreamItem, searchHistory: List<String>): Double {
        if (searchHistory.isEmpty()) return 0.0
        val title = video.title?.lowercase() ?: return 0.0
        val channelName = video.uploaderName?.lowercase() ?: ""

        var score = 0.0
        for (query in searchHistory.take(5)) {
            val queryLower = query.lowercase()
            val tokens = queryLower.split(" ").filter { it.length > 2 }
            var matchCount = 0
            for (token in tokens) {
                if (title.contains(token) || channelName.contains(token)) {
                    matchCount++
                }
            }
            if (tokens.isNotEmpty()) {
                score += (matchCount.toDouble() / tokens.size) * 0.2
            }
        }
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Category match: check if video title/channel matches the selected category.
     */
    private fun calculateCategoryMatch(video: StreamItem, category: String): Double {
        val title = video.title?.lowercase() ?: ""
        val channelName = video.uploaderName?.lowercase() ?: ""
        val categoryLower = category.lowercase()

        // Simple keyword matching as fallback
        return when {
            title.contains(categoryLower) -> 0.8
            channelName.contains(categoryLower) -> 0.6
            else -> 0.0
        }
    }

    private fun extractChannelId(video: StreamItem): String? {
        return video.uploaderUrl?.toID()
    }
}
