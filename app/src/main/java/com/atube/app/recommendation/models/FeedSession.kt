package com.atube.app.recommendation.models

import com.atube.app.api.obj.StreamItem
import java.util.UUID

/**
 * Represents a single Home feed session.
 *
 * Every Home visit creates or resumes a session.
 * The session tracks what has been shown, clicked, and dismissed
 * to avoid duplicates and preserve ranking context.
 */
data class FeedSession(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long = System.currentTimeMillis(),
    val category: String = "All",
    val shownIds: MutableSet<String> = mutableSetOf(),
    val clickedIds: MutableSet<String> = mutableSetOf(),
    val dismissedIds: MutableSet<String> = mutableSetOf(),
    val watchSignals: MutableList<WatchSignal> = mutableListOf(),
    var pageIndex: Int = 0,
    var sessionSeed: Long = System.currentTimeMillis(),
    val region: String = "IN",
    var hasMore: Boolean = true
) {
    /** Add a video ID to the shown set. */
    fun markShown(videoId: String) {
        shownIds.add(videoId)
    }

    /** Record a click on a video. */
    fun markClicked(videoId: String) {
        clickedIds.add(videoId)
    }

    /** Record a dismissal (not interested). */
    fun markDismissed(videoId: String) {
        dismissedIds.add(videoId)
    }

    /** Record a watch signal. */
    fun recordWatchSignal(signal: WatchSignal) {
        watchSignals.add(signal)
    }

    /** Whether this video has already been shown in this session. */
    fun hasShown(videoId: String): Boolean = videoId in shownIds

    /** Whether this video was dismissed. */
    fun isDismissed(videoId: String): Boolean = videoId in dismissedIds

    /** Generate a new seed for re-ranking on refresh. */
    fun newSeed() {
        sessionSeed = System.currentTimeMillis()
    }
}

/**
 * A watch behavior signal recorded during a session.
 */
data class WatchSignal(
    val videoId: String,
    val channelId: String?,
    val watchDuration: Long,
    val videoDuration: Long,
    val completionRatio: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /** Create from watch position and video duration. */
        fun fromPosition(videoId: String, channelId: String?, positionMillis: Long, durationSeconds: Long): WatchSignal {
            val durationMillis = durationSeconds * 1000
            val ratio = if (durationMillis > 0) {
                (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
            } else 0f
            return WatchSignal(
                videoId = videoId,
                channelId = channelId,
                watchDuration = positionMillis,
                videoDuration = durationMillis,
                completionRatio = ratio
            )
        }
    }
}
