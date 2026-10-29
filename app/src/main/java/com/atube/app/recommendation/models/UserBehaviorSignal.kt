package com.atube.app.recommendation.models

/**
 * User behavior events tracked locally for recommendation personalization.
 *
 * Each event records only the information needed for ranking —
 * no personal data is sent to external servers.
 */
enum class BehaviorEvent {
    IMPRESSION,
    CLICK,
    PLAY,
    PAUSE,
    WATCH_PROGRESS,
    WATCH_COMPLETE,
    SKIP,
    LIKE,
    DISLIKE,
    NOT_INTERESTED,
    CHANNEL_BLOCK,
    SEARCH,
    SUBSCRIBE,
    UNSUBSCRIBE
}

/**
 * A single behavior signal with associated metadata.
 */
data class UserBehaviorSignal(
    val event: BehaviorEvent,
    val videoId: String,
    val channelId: String? = null,
    val topic: String? = null,
    val watchDuration: Long = 0,
    val videoDuration: Long = 0,
    val completionRatio: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /** Event weight mapping — strongest behavior gets strongest influence. */
        val EVENT_WEIGHTS: Map<BehaviorEvent, Float> = mapOf(
            BehaviorEvent.IMPRESSION to 0.01f,
            BehaviorEvent.CLICK to 0.15f,
            BehaviorEvent.PLAY to 0.10f,
            BehaviorEvent.WATCH_PROGRESS to 0.35f,  // adjusted by completion
            BehaviorEvent.WATCH_COMPLETE to 1.00f,
            BehaviorEvent.LIKE to 0.80f,
            BehaviorEvent.SUBSCRIBE to 1.00f,
            BehaviorEvent.PAUSE to 0.0f,
            BehaviorEvent.SKIP to -0.25f,
            BehaviorEvent.DISLIKE to -0.50f,
            BehaviorEvent.NOT_INTERESTED to -1.50f,
            BehaviorEvent.CHANNEL_BLOCK to -3.00f,
            BehaviorEvent.UNSUBSCRIBE to -0.50f,
            BehaviorEvent.SEARCH to 0.10f
        )

        /** Weight for watch progress based on completion ratio. */
        fun watchWeight(completionRatio: Float): Float = when {
            completionRatio >= 0.90f -> 1.00f
            completionRatio >= 0.75f -> 0.85f
            completionRatio >= 0.50f -> 0.60f
            completionRatio >= 0.25f -> 0.35f
            completionRatio >= 0.10f -> 0.15f
            else -> -0.35f  // early exit — negative signal
        }
    }
}
