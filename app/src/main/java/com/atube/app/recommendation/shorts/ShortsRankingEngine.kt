package com.atube.app.recommendation.shorts

import com.atube.app.api.obj.StreamItem
import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.UserInterestProfile
import kotlin.math.ln
import kotlin.math.max

/** Deterministic PRD baseline. Missing signals are omitted and weights renormalized. */
internal object ShortsRankingEngine {
    private const val FRESHNESS = 0.25f
    private const val POPULARITY = 0.25f
    private const val INTEREST = 0.20f
    private const val CREATOR = 0.15f
    private const val TREND = 0.10f
    private const val QUALITY = 0.05f

    fun rank(videos: List<StreamItem>, profile: UserInterestProfile? = null,
             now: Long = System.currentTimeMillis()): List<StreamItem> {
        val maxViews = videos.mapNotNull { it.views }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        return videos.sortedByDescending { video ->
            var total = 0f
            var weight = 0f
            fun add(w: Float, value: Float?) {
                if (value != null && value.isFinite()) {
                    total += w * value.coerceIn(0f, 1f)
                    weight += w
                }
            }
            val publishedAt = video.uploaded.takeIf { it > 0L }
            add(FRESHNESS, publishedAt?.let {
                val ageHours = max(0L, now - it) / 3_600_000f
                1f / (1f + ageHours / 72f)
            })
            add(POPULARITY, video.views?.let {
                (ln(it.coerceAtLeast(0L).toDouble() + 1.0) /
                    ln(maxViews.toDouble() + 1.0)).toFloat()
            })
            val metadata = listOfNotNull(video.title, video.shortDescription)
                .joinToString(" ").lowercase()
            val interestScore = profile?.topicScores
                ?.filterKeys { topic -> topic.isNotBlank() && metadata.contains(topic.lowercase()) }
                ?.values?.maxOrNull()
            add(INTEREST, interestScore)
            val creatorId = video.uploaderUrl?.toID()
            add(CREATOR, creatorId?.let { profile?.channelScore(it) })
            // Current provider data has no reliable historical velocity or quality signal.
            // Do not fabricate those values; renormalize the available signals.
            if (weight == 0f) 0f else total / weight
        }
    }
}
