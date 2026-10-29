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

    // In-memory stores (persisted via Room in a production system)
    private val notInterestedVideoIds: MutableSet<String> = mutableSetOf()
    private val blockedChannelIds: MutableSet<String> = mutableSetOf()
    private val dismissedVideoIds: MutableSet<String> = mutableSetOf()

    /**
     * Mark a video as "not interested".
     * The video will not be shown again in the current session
     * and receives a large penalty in future ranking.
     */
    fun markNotInterested(videoId: String) {
        notInterestedVideoIds.add(videoId)
    }

    /**
     * Block a channel from recommendations.
     * Videos from this channel will not appear in the recommendation feed.
     * This only affects recommendations, not direct search results.
     */
    fun blockChannel(channelId: String) {
        blockedChannelIds.add(channelId)
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
