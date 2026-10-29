package com.atube.app.recommendation.models

import com.atube.app.api.obj.StreamItem

/**
 * Sealed interface for items that can appear in the Home feed.
 *
 * This allows a single vertical RecyclerView to contain
 * multiple content types (videos, shelves, loading indicators).
 */
sealed interface HomeFeedItem {

    /** A normal video card. */
    data class Video(
        val data: StreamItem,
        val source: String = "recommendation",
        val position: Int = 0
    ) : HomeFeedItem

    /** A horizontal shelf of Shorts. */
    data class ShortsShelf(
        val videos: List<StreamItem>
    ) : HomeFeedItem

    /** A horizontal shelf of continue-watching videos. */
    data class ContinueWatching(
        val videos: List<StreamItem>
    ) : HomeFeedItem

    /** A loading placeholder at the bottom of the feed. */
    data class Loading(
        val id: String = "loading_${System.currentTimeMillis()}"
    ) : HomeFeedItem

    /** End-of-feed indicator. */
    data class AllCaughtUp(
        val id: String = "all_caught_up"
    ) : HomeFeedItem

    /** Skeleton placeholder card shown during loading. */
    data class Skeleton(
        val id: String
    ) : HomeFeedItem
}
