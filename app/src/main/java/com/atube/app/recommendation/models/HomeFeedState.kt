package com.atube.app.recommendation.models

/**
 * Explicit UI status for the Home feed.
 *
 * This replaces implicit isEmpty() checks with an explicit state machine.
 * The transition is always:
 *
 *   INITIALIZING -> LOADING -> SUCCESS / EMPTY / ERROR / OFFLINE
 *
 * NEVER: LOADING -> items.isEmpty() -> EMPTY
 */
enum class HomeUiStatus {
    INITIALIZING,
    LOADING,
    SUCCESS,
    EMPTY,
    ERROR,
    OFFLINE
}

/**
 * Dedicated UI state for the Home feed.
 *
 * This replaces multiple independent LiveData objects with a single
 * state class, making the feed easier to reason about.
 */
data class HomeFeedState(
    val items: List<HomeFeedItem> = emptyList(),
    val status: HomeUiStatus = HomeUiStatus.INITIALIZING,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = true,
    val selectedCategory: String = "All",
    val error: Throwable? = null,
    val sessionId: String = ""
) {
    /** Only true when the API returned a successful but genuinely empty result. */
    val isGenuinelyEmpty: Boolean
        get() = status == HomeUiStatus.EMPTY
}
