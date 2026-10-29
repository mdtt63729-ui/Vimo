package com.atube.app.recommendation.ranking

import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.UserInterestProfile

/**
 * Post-ranking diversity filter.
 *
 * After the ranking engine sorts candidates by score, the diversity
 * engine applies rules to prevent:
 * - Too many videos from the same channel consecutively
 * - Too many videos of the same topic consecutively
 * - Shorts dominating the normal feed
 *
 * This is a separate stage from ranking — it does not re-score,
 * it only reorders by inserting diversity gaps.
 */
class DiversityEngine(
    private val maxSameChannelInWindow: Int = 2,
    private val channelWindow: Int = 8,
    private val maxSameTopicInWindow: Int = 3,
    private val topicWindow: Int = 5
) {

    /**
     * Apply diversity rules to a ranked list of candidates.
     *
     * @param ranked Candidates already sorted by score (highest first).
     * @param profile User interest profile for topic extraction.
     * @return Diversified list with the same elements, reordered.
     */
    fun diversify(
        ranked: List<RecommendationCandidate>,
        profile: UserInterestProfile
    ): List<RecommendationCandidate> {
        if (ranked.size <= 3) return ranked

        val result = mutableListOf<RecommendationCandidate>()
        val remaining = ranked.toMutableList()
        val recentChannels = ArrayDeque<String>()
        val recentTopics = ArrayDeque<String>()

        while (remaining.isNotEmpty()) {
            var placed = false

            // Find the best candidate that doesn't violate diversity rules
            for ((index, candidate) in remaining.withIndex()) {
                val channelId = candidate.video.uploaderUrl?.toID() ?: ""
                val topics = extractTopics(candidate, profile)

                val channelViolations = recentChannels.count { it == channelId }
                val topicViolations = topics.count { topic -> recentTopics.count { it == topic } > 0 }

                if (channelViolations < maxSameChannelInWindow && topicViolations < maxSameTopicInWindow) {
                    // This candidate is OK — add it
                    result.add(candidate)
                    remaining.removeAt(index)

                    // Track channel
                    recentChannels.addLast(channelId)
                    if (recentChannels.size > channelWindow) recentChannels.removeFirst()

                    // Track topics
                    topics.forEach { topic ->
                        recentTopics.addLast(topic)
                        if (recentTopics.size > topicWindow * 2) recentTopics.removeFirst()
                    }

                    placed = true
                    break
                }
            }

            if (!placed) {
                // All remaining candidates violate diversity — add the best one anyway
                result.add(remaining.removeAt(0))
                val channelId = result.last().video.uploaderUrl?.toID() ?: ""
                recentChannels.addLast(channelId)
                if (recentChannels.size > channelWindow) recentChannels.removeFirst()
            }
        }

        return result
    }

    /**
     * Insert Shorts shelves into the feed at regular intervals.
     *
     * @param feed The normal video feed.
     * @param shorts The Shorts candidates.
     * @param interval Number of normal videos between Shorts shelves.
     * @param shelfSize Number of Shorts per shelf.
     * @return Feed with Shorts shelves inserted.
     */
    fun insertShortsShelves(
        feed: List<RecommendationCandidate>,
        shorts: List<RecommendationCandidate>,
        interval: Int = 6,
        shelfSize: Int = 6
    ): List<List<RecommendationCandidate>> {
        if (shorts.isEmpty()) return listOf(feed)

        val result = mutableListOf<MutableList<RecommendationCandidate>>()
        var shortIndex = 0
        var videoCount = 0

        for (candidate in feed) {
            if (result.isEmpty()) result.add(mutableListOf())

            result.last().add(candidate)
            videoCount++

            // Insert a Shorts shelf every `interval` videos
            if (videoCount % interval == 0 && shortIndex < shorts.size) {
                val shelf = shorts.subList(
                    shortIndex,
                    minOf(shortIndex + shelfSize, shorts.size)
                ).toList()
                result.add(shelf.toMutableList())
                shortIndex += shelfSize
            }
        }

        return result
    }

    private fun extractTopics(
        candidate: RecommendationCandidate,
        profile: UserInterestProfile
    ): List<String> {
        val title = candidate.video.title?.lowercase() ?: return emptyList()
        return profile.topTopics(10)
            .filter { (topic, _) -> title.contains(topic.lowercase()) }
            .map { it.first }
    }
}
