package com.atube.app.recommendation.feedback

import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.FeedSession
import com.atube.app.recommendation.models.UserInterestProfile

/**
 * Manages persistent local negative feedback from the user.
 *
 * Handles:
 * - "Not interested" on a specific video
 * - "Don't recommend this channel" 
 * - General dismissal (skip/swipe away)
 *
 * All feedback is stored locally — nothing is sent to external servers.
 */
object RecommendationFeedbackManager {

    // Persisted, not just in memory: feedback that vanishes when the app restarts
    // is why "Not interested" and "Don't recommend channel" appeared to do nothing.
    private val notInterestedVideoIds: MutableSet<String> =
        load(KEY_NOT_INTERESTED).toMutableSet()
    private val blockedChannelIds: MutableSet<String> =
        load(KEY_BLOCKED_CHANNELS).toMutableSet()
    private val dismissedVideoIds: MutableSet<String> = mutableSetOf()

    private const val KEY_NOT_INTERESTED = "rec_not_interested"
    private const val KEY_BLOCKED_CHANNELS = "rec_blocked_channels"

    private fun load(key: String): Set<String> =
        com.atube.app.helpers.PreferenceHelper.getString(key, "")
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun save(key: String, values: Set<String>) {
        com.atube.app.helpers.PreferenceHelper.putString(key, values.joinToString("\n"))
    }

    /**
     * Mark a video as "not interested".
     * The video will not be shown again in the current session
     * and receives a large penalty in future ranking.
     */
    fun markNotInterested(videoId: String) {
        notInterestedVideoIds.add(videoId)
        save(KEY_NOT_INTERESTED, notInterestedVideoIds)
    }

    /**
     * Block a channel from recommendations.
     * Videos from this channel will not appear in the recommendation feed.
     * This only affects recommendations, not direct search results.
     */
    fun blockChannel(channelId: String) {
        if (channelId.isBlank()) return
        blockedChannelIds.add(channelId)
        save(KEY_BLOCKED_CHANNELS, blockedChannelIds)
    }

    /**
     * Record a dismissal (user skipped/swiped past the video).
     * This is a weaker signal than "not interested".
     */
    fun dismissVideo(videoId: String) {
        dismissedVideoIds.add(videoId)
    }

    /** Check if a video was marked "not interested". */
    fun isNotInterested(videoId: String): Boolean = videoId in notInterestedVideoIds

    /** Check if a channel is blocked from recommendations. */
    fun isChannelBlocked(channelId: String): Boolean = channelId in blockedChannelIds

    /** Check if a video was dismissed. */
    fun isDismissed(videoId: String): Boolean = videoId in dismissedVideoIds

    /** Get all blocked channel IDs. */
    fun getBlockedChannels(): Set<String> = blockedChannelIds.toSet()

    /** Get all dismissed video IDs. */
    fun getDismissedVideos(): Set<String> = dismissedVideoIds.toSet()

    /** Get all "not interested" video IDs. */
    fun getNotInterestedVideos(): Set<String> = notInterestedVideoIds.toSet()

    /**
     * Apply negative feedback to the user interest profile.
     * Penalize channels associated with dismissed/not-interested videos.
     */
    fun applyToProfile(
        profile: UserInterestProfile,
        dismissedVideoChannelIds: Map<String, String>
    ) {
        for ((videoId, channelId) in dismissedVideoChannelIds) {
            if (isNotInterested(videoId) || isDismissed(videoId)) {
                profile.penalizeChannel(channelId, 0.05f)
            }
        }
    }

    /** Clear all feedback (for testing/debugging). */
    fun clearAll() {
        notInterestedVideoIds.clear()
        blockedChannelIds.clear()
        dismissedVideoIds.clear()
    }
}
