package com.atube.app.recommendation.ranking

import com.atube.app.recommendation.models.BehaviorEvent
import com.atube.app.recommendation.models.TopicAffinity
import com.atube.app.recommendation.models.UserBehaviorSignal
import com.atube.app.recommendation.models.UserInterestProfile
import kotlin.math.ln

/**
 * Manages user interest scoring with session + long-term profiles.
 *
 * Tracks behavioral signals (watch, click, skip, like, etc.) and
 * converts them into topic/channel/category affinity scores.
 *
 * Features:
 * - Session interest profile (fast adaptation)
 * - Long-term interest profile (stable preferences)
 * - Temporal decay (recent matters more)
 * - Diminishing returns (prevents one topic from taking over)
 * - Topic clustering (semantic grouping)
 */
class UserInterestEngine {

    private val longTermProfile = UserInterestProfile()
    private val sessionProfile = UserInterestProfile()

    /** Recent behavior signals for the current session. */
    private val sessionSignals = mutableListOf<UserBehaviorSignal>()

    /**
     * Record a behavior signal and update profiles.
     */
    fun recordSignal(signal: UserBehaviorSignal) {
        sessionSignals.add(signal)

        val weight = if (signal.event == BehaviorEvent.WATCH_PROGRESS) {
            UserBehaviorSignal.watchWeight(signal.completionRatio)
        } else {
            UserBehaviorSignal.EVENT_WEIGHTS[signal.event] ?: 0f
        }

        if (weight == 0f) return

        // Update topic scores using semantic clustering
        val topic = signal.topic
        if (topic != null) {
            val cluster = TopicAffinity.clusterOf(topic) ?: topic
            // Diminishing returns: log-scale the interaction count
            val currentCount = getSessionInteractionCount(cluster)
            val diminishingFactor = 1.0 / (1.0 + ln(currentCount.toDouble() + 1))

            val adjustedWeight = weight * diminishingFactor.toFloat()

            if (weight > 0) {
                sessionProfile.boostTopic(cluster, adjustedWeight)
                longTermProfile.boostTopic(cluster, adjustedWeight * 0.3f)
            } else {
                sessionProfile.boostTopic(cluster, adjustedWeight)
                longTermProfile.boostTopic(cluster, adjustedWeight * 0.3f)
            }
        }

        // Update channel scores
        if (signal.channelId != null) {
            if (weight > 0) {
                sessionProfile.boostChannel(signal.channelId, weight * 0.5f)
                longTermProfile.boostChannel(signal.channelId, weight * 0.15f)
            } else {
                sessionProfile.penalizeChannel(signal.channelId, -weight * 0.5f)
                longTermProfile.penalizeChannel(signal.channelId, -weight * 0.15f)
            }
        }
    }

    /**
     * Get the combined interest profile (session + long-term).
     * Session gets 60% weight, long-term gets 40%.
     */
    fun getCombinedProfile(): UserInterestProfile {
        val combined = UserInterestProfile()

        // Merge topic scores — session weighted higher
        val allTopics = sessionProfile.topicScores.keys + longTermProfile.topicScores.keys
        for (topic in allTopics) {
            val sessionScore = sessionProfile.topicScores[topic] ?: 0f
            val longScore = longTermProfile.topicScores[topic] ?: 0f
            combined.topicScores[topic] = sessionScore * 0.6f + longScore * 0.4f
        }

        // Merge channel scores
        val allChannels = sessionProfile.channelScores.keys + longTermProfile.channelScores.keys
        for (channel in allChannels) {
            val sessionScore = sessionProfile.channelScores[channel] ?: 0f
            val longScore = longTermProfile.channelScores[channel] ?: 0f
            combined.channelScores[channel] = sessionScore * 0.6f + longScore * 0.4f
        }

        // Merge category scores
        val allCategories = sessionProfile.categoryScores.keys + longTermProfile.categoryScores.keys
        for (cat in allCategories) {
            val sessionScore = sessionProfile.categoryScores[cat] ?: 0f
            val longScore = longTermProfile.categoryScores[cat] ?: 0f
            combined.categoryScores[cat] = sessionScore * 0.6f + longScore * 0.4f
        }

        return combined
    }

    /**
     * Apply temporal decay to the long-term profile.
     * Called periodically to reduce old interest influence.
     */
    fun applyDecay() {
        longTermProfile.applyDecay(0.95f)
    }

    /**
     * Reset session profile (e.g., on fresh app launch or category change).
     */
    fun resetSession() {
        sessionProfile.topicScores.clear()
        sessionProfile.channelScores.clear()
        sessionProfile.categoryScores.clear()
        sessionSignals.clear()
    }

    /**
     * Get the number of interactions for a topic in the current session.
     */
    private fun getSessionInteractionCount(topic: String): Int {
        return sessionSignals.count { signal ->
            signal.topic != null &&
                (TopicAffinity.clusterOf(signal.topic) ?: signal.topic) == topic
        }
    }

    /** Whether the user has any interest data. */
    val hasData: Boolean
        get() = longTermProfile.hasData || sessionProfile.hasData

    /** Get session profile. */
    fun getSessionProfile(): UserInterestProfile = sessionProfile

    /** Get long-term profile. */
    fun getLongTermProfile(): UserInterestProfile = longTermProfile
}
