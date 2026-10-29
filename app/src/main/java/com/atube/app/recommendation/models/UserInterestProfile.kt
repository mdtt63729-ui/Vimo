package com.atube.app.recommendation.models

/**
 * Lightweight local recommendation profile.
 *
 * Stores topic, channel, and category affinity scores.
 * Updated based on user behavior (search, click, watch duration,
 * completion, like, subscription, dismissal).
 *
 * All data stays local — nothing is sent to external servers.
 */
data class UserInterestProfile(
    val topicScores: MutableMap<String, Float> = mutableMapOf(),
    val channelScores: MutableMap<String, Float> = mutableMapOf(),
    val categoryScores: MutableMap<String, Float> = mutableMapOf(),
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Apply exponential decay to all scores.
     *
     * This prevents old interests from permanently dominating.
     * Recent behavior matters more.
     *
     * @param decayFactor Multiplier in (0, 1). 0.95 = 5% decay per call.
     */
    fun applyDecay(decayFactor: Float = 0.95f) {
        topicScores.entries.removeAll { entry ->
            entry.setValue(entry.value * decayFactor)
            entry.value < MIN_SCORE_THRESHOLD
        }
        channelScores.entries.removeAll { entry ->
            entry.setValue(entry.value * decayFactor)
            entry.value < MIN_SCORE_THRESHOLD
        }
        categoryScores.entries.removeAll { entry ->
            entry.setValue(entry.value * decayFactor)
            entry.value < MIN_SCORE_THRESHOLD
        }
    }

    /** Record a positive signal for a topic. */
    fun boostTopic(topic: String, amount: Float = 0.1f) {
        topicScores[topic] = (topicScores[topic] ?: 0f) + amount
    }

    /** Record a positive signal for a channel. */
    fun boostChannel(channelId: String, amount: Float = 0.1f) {
        channelScores[channelId] = (channelScores[channelId] ?: 0f) + amount
    }

    /** Record a negative signal for a channel (skip/dismiss). */
    fun penalizeChannel(channelId: String, amount: Float = 0.05f) {
        val current = channelScores[channelId] ?: 0f
        channelScores[channelId] = (current - amount).coerceAtLeast(0f)
    }

    /** Record a positive signal for a category. */
    fun boostCategory(category: String, amount: Float = 0.1f) {
        categoryScores[category] = (categoryScores[category] ?: 0f) + amount
    }

    /** Get topic affinity score, normalized to [0, 1]. */
    fun topicScore(topic: String): Float {
        val max = topicScores.values.maxOrNull() ?: 1f
        return if (max > 0) (topicScores[topic] ?: 0f) / max else 0f
    }

    /** Get channel affinity score, normalized to [0, 1]. */
    fun channelScore(channelId: String): Float {
        val max = channelScores.values.maxOrNull() ?: 1f
        return if (max > 0) (channelScores[channelId] ?: 0f) / max else 0f
    }

    /** Get category affinity score, normalized to [0, 1]. */
    fun categoryScore(category: String): Float {
        val max = categoryScores.values.maxOrNull() ?: 1f
        return if (max > 0) (categoryScores[category] ?: 0f) / max else 0f
    }

    /** Top N topics by score. */
    fun topTopics(n: Int = 5): List<Pair<String, Float>> {
        return topicScores.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    /** Top N channels by score. */
    fun topChannels(n: Int = 5): List<Pair<String, Float>> {
        return channelScores.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    /** Whether the user has any interest data. */
    val hasData: Boolean
        get() = topicScores.isNotEmpty() || channelScores.isNotEmpty()

    companion object {
        private const val MIN_SCORE_THRESHOLD = 0.01f
    }
}
