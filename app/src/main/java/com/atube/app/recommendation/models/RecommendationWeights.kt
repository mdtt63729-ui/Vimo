package com.atube.app.recommendation.models

/**
 * Configurable weights for the recommendation scoring formula.
 *
 * Allows tuning the ranking without rewriting logic.
 * All weights are in the range [0, 1] and are multiplied by their
 * respective normalized signal scores.
 */
data class RecommendationWeights(
    val freshness: Double = 0.15,
    val popularity: Double = 0.10,
    val subscription: Double = 0.20,
    val channelAffinity: Double = 0.15,
    val topicAffinity: Double = 0.15,
    val searchAffinity: Double = 0.10,
    val relatedness: Double = 0.15,
    val trending: Double = 0.10,
    val novelty: Double = 0.05,
    val completionAffinity: Double = 0.10,
    val categoryMatch: Double = 0.10,
    val multiSourceBoost: Double = 0.05,
    val watchedPenalty: Double = 0.25,
    val repeatedChannelPenalty: Double = 0.15,
    val dismissedPenalty: Double = 0.30,
    val stalePenalty: Double = 0.05,
    val explorationFactor: Double = 0.10
) {
    companion object {
        /** Default weights for general recommendations. */
        val DEFAULT = RecommendationWeights()

        /** Weights favouring fresh content. */
        val FRESH_FOCUSED = RecommendationWeights(
            freshness = 0.30,
            popularity = 0.05,
            subscription = 0.15,
            stalePenalty = 0.15
        )

        /** Weights for cold-start (no history) users. */
        val COLD_START = RecommendationWeights(
            freshness = 0.20,
            popularity = 0.25,
            subscription = 0.0,
            channelAffinity = 0.0,
            topicAffinity = 0.0,
            searchAffinity = 0.0,
            relatedness = 0.0,
            trending = 0.30,
            novelty = 0.15,
            explorationFactor = 0.20
        )
    }
}
