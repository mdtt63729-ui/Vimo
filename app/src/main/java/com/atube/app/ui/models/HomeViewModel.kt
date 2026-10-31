package com.atube.app.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.PlaylistsHelper
import com.atube.app.api.SubscriptionHelper
import com.atube.app.api.TrendingCategory
import com.atube.app.api.obj.Playlists
import com.atube.app.api.obj.StreamItem
import com.atube.app.constants.PreferenceKeys
import com.atube.app.db.DatabaseHelper
import com.atube.app.db.DatabaseHolder
import com.atube.app.db.obj.PlaylistBookmark
import com.atube.app.extensions.runSafely
import com.atube.app.extensions.updateIfChanged
import com.atube.app.helpers.PlayerHelper
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.recommendation.HomeFeedCoordinator
import com.atube.app.recommendation.models.HomeFeedItem
import com.atube.app.recommendation.models.HomeFeedState
import com.atube.app.recommendation.models.HomeUiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class HomeViewModel : ViewModel() {
    private val hideWatched
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.HIDE_WATCHED_FROM_FEED,
            false
        )
    private val showUpcoming
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SHOW_UPCOMING_IN_FEED,
            true
        )

    // === Existing LiveData (kept for backward compatibility) ===
    val trending: MutableLiveData<Pair<TrendingCategory, TrendsViewModel.TrendingStreams>> =
        MutableLiveData(null)
    val feed: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val bookmarks: MutableLiveData<List<PlaylistBookmark>> = MutableLiveData(null)
    val playlists: MutableLiveData<List<Playlists>> = MutableLiveData(null)
    val continueWatching: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(true)
    val loadedSuccessfully: MutableLiveData<Boolean> = MutableLiveData(false)

    // === New recommendation feed LiveData ===
    val feedState: MutableLiveData<HomeFeedState> = MutableLiveData(HomeFeedState())
    val selectedCategory: MutableLiveData<String> = MutableLiveData("All")

    private val sections get() = listOf(trending, feed, bookmarks, playlists, continueWatching)

    private var loadHomeJob: Job? = null
    private var recFeedJob: Job? = null

    /**
     * What each chip already loaded, so going All -> Explore -> All puts the old
     * list straight back instead of fetching it again and flashing the skeletons.
     */
    private val categoryCache = mutableMapOf<String, List<HomeFeedItem>>()

    private var loadMoreJob: Job? = null

    // Recommendation engine coordinator
    private val homeFeedCoordinator = HomeFeedCoordinator()

    // === Existing loadHomeFeed (kept for backward compatibility) ===
    fun loadHomeFeed(
        context: Context,
        subscriptionsViewModel: SubscriptionsViewModel,
        visibleItems: Set<String>,
        onUnusualLoadTime: () -> Unit
    ) {
        isLoading.value = true

        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            val result = async {
                awaitAll(
                    async { if (visibleItems.contains(TRENDING)) loadTrending(context) },
                    async { if (visibleItems.contains(FEATURED)) loadFeed(subscriptionsViewModel) },
                    async { if (visibleItems.contains(BOOKMARKS)) loadBookmarks() },
                    async { if (visibleItems.contains(PLAYLISTS)) loadPlaylists() },
                    async { if (visibleItems.contains(WATCHING)) loadVideosToContinueWatching() }
                )
                loadedSuccessfully.value = sections.any { it.value != null }
                isLoading.value = false
            }

            withContext(Dispatchers.IO) {
                delay(UNUSUAL_LOAD_TIME_MS)
                if (result.isActive) {
                    onUnusualLoadTime.invoke()
                }
            }
        }
    }

    // === New recommendation feed methods ===

    /**
     * Load the initial recommendation feed.
     * Shows cached feed immediately if available, then fetches fresh.
     */
    fun loadRecommendationFeed(context: Context, forceRefresh: Boolean = false) {
        val region = PreferenceHelper.getTrendingRegion(context)

        // Show cached feed immediately if available
        val cached = homeFeedCoordinator.getCachedFeed()
        if (cached != null && !forceRefresh) {
            feedState.value = HomeFeedState(
                items = cached,
                status = HomeUiStatus.SUCCESS,
                hasMore = true,
                selectedCategory = selectedCategory.value ?: "All"
            )
        } else {
            // Emit explicit LOADING state with skeleton items — NOT empty
            feedState.value = HomeFeedState(
                items = createSkeletonItems(6),
                status = HomeUiStatus.LOADING,
                hasMore = true,
                selectedCategory = selectedCategory.value ?: "All"
            )
        }

        recFeedJob?.cancel()
        recFeedJob = viewModelScope.launch {
            try {
                // A dead or hanging instance used to leave the skeleton running
                // for ever. Twenty seconds is enough for a real request; after
                // that the screen shows an error instead of pretending to load.
                val state = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    homeFeedCoordinator.loadInitialFeed(region, forceRefresh)
                }
                if (state == null) {
                    feedState.postValue(
                        HomeFeedState(
                            status = HomeUiStatus.ERROR,
                            selectedCategory = selectedCategory.value ?: "All"
                        )
                    )
                    return@launch
                }
                // If the API returned items, emit SUCCESS
                // If it returned genuinely empty, emit EMPTY
                if (state.items.isNotEmpty()) {
                    // A plain (non-forced) load keeps whatever is already on screen
                    // and appends only new videos: replacing the list a second after
                    // it appeared is what made the feed look like it was swapping
                    // videos under the user's thumb.
                    val merged = if (forceRefresh) {
                        state.copy(status = HomeUiStatus.SUCCESS)
                    } else {
                        mergeWithVisible(feedState.value, state.copy(status = HomeUiStatus.SUCCESS))
                    }
                    feedState.postValue(merged)
                } else {
                    feedState.postValue(state.copy(status = HomeUiStatus.EMPTY))
                }
            } catch (e: Exception) {
                feedState.postValue(
                    HomeFeedState(
                        status = HomeUiStatus.ERROR,
                        error = e,
                        selectedCategory = selectedCategory.value ?: "All"
                    )
                )
            }
        }
    }

    /**
     * Load the next page of recommendations (pagination).
     */
    fun loadMoreRecommendations(context: Context) {
        val currentState = feedState.value
        if (currentState?.isLoadingMore == true || currentState?.hasMore == false) return
        if (loadMoreJob?.isActive == true) return

        // Update state to show loading
        feedState.value = currentState?.copy(isLoadingMore = true)

        val region = PreferenceHelper.getTrendingRegion(context)
        loadMoreJob = viewModelScope.launch {
            try {
                val state = homeFeedCoordinator.loadMore(region)
                if (state != null) {
                    // keep the feed on screen while a new page is appended
                    feedState.postValue(
                        state.copy(
                            status = if (state.items.isEmpty()) {
                                currentState?.status ?: HomeUiStatus.SUCCESS
                            } else {
                                HomeUiStatus.SUCCESS
                            },
                            isLoadingMore = false
                        )
                    )
                } else {
                    feedState.postValue(currentState?.copy(isLoadingMore = false, hasMore = false))
                }
            } catch (e: Exception) {
                feedState.postValue(currentState?.copy(isLoadingMore = false))
            }
        }
    }

    /**
     * Load recommendations for a specific category.
     * Does NOT recreate the fragment — just re-ranks.
     */
    fun loadCategory(context: Context, category: String) {
        selectedCategory.value = category

        // Already loaded once: restore it and stop. Nothing is fetched and the
        // thumbnails come back from Coil's cache, so the switch is instant.
        val remembered = categoryCache[category]
        if (remembered != null && remembered.isNotEmpty()) {
            recFeedJob?.cancel()
            feedState.value = HomeFeedState(
                items = remembered,
                status = HomeUiStatus.SUCCESS,
                hasMore = true,
                selectedCategory = category
            )
            return
        }

        val region = PreferenceHelper.getTrendingRegion(context)

        // Emit explicit LOADING state with skeleton items immediately
        feedState.value = HomeFeedState(
            items = createSkeletonItems(6),
            status = HomeUiStatus.LOADING,
            selectedCategory = category
        )

        // Cancel any previous category request (stale request protection)
        recFeedJob?.cancel()
        recFeedJob = viewModelScope.launch {
            try {
                val state = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    homeFeedCoordinator.loadCategory(category, region)
                }
                if (state == null) {
                    feedState.postValue(
                        HomeFeedState(
                            status = HomeUiStatus.ERROR,
                            selectedCategory = category
                        )
                    )
                    return@launch
                }
                // Stale request protection: only update if this category is still selected
                if (selectedCategory.value != category) return@launch
                if (state.items.isNotEmpty()) {
                    categoryCache[category] = state.items
                    feedState.postValue(state.copy(status = HomeUiStatus.SUCCESS))
                } else {
                    feedState.postValue(state.copy(status = HomeUiStatus.EMPTY))
                }
            } catch (e: Exception) {
                if (selectedCategory.value != category) return@launch
                feedState.postValue(
                    HomeFeedState(
                        status = HomeUiStatus.ERROR,
                        error = e,
                        selectedCategory = category
                    )
                )
            }
        }
    }

    /**
     * Record a click on a video (for recommendation learning).
     */
    fun recordClick(videoId: String) {
        homeFeedCoordinator.recordClick(videoId)
    }

    /**
     * Record a dismissal (not interested).
     */
    fun recordDismissal(videoId: String) {
        homeFeedCoordinator.recordDismissal(videoId)
    }

    /**
     * Record a watch signal for learning.
     */
    fun recordWatchSignal(
        videoId: String,
        channelId: String?,
        watchDuration: Long,
        videoDuration: Long,
        completionRatio: Float
    ) {
        homeFeedCoordinator.recordWatchSignal(
            videoId, channelId, watchDuration, videoDuration, completionRatio
        )
    }

    /**
     * Create skeleton placeholder items for the loading state.
     * These show shimmer-animated cards while the API request is running.
     */
    private fun createSkeletonItems(count: Int): List<HomeFeedItem> {
        val timestamp = System.currentTimeMillis()
        return (0 until count).map { index ->
            HomeFeedItem.Skeleton(id = "skeleton_${timestamp}_$index")
        }
    }

    // === Existing private loaders (unchanged) ===

    private suspend fun loadTrending(context: Context) {
        val region = PreferenceHelper.getTrendingRegion(context)
        val category = PreferenceHelper.getString(
            PreferenceKeys.TRENDING_CATEGORY,
            TrendingCategory.LIVE.name
        ).let { TrendingCategory.valueOf(it) }

        runSafely(
            onSuccess = { videos ->
                trending.updateIfChanged(
                    Pair(
                        category,
                        TrendsViewModel.TrendingStreams(region, videos)
                    )
                )
            },
            ioBlock = {
                MediaServiceRepository.instance.getTrending(region, category)
            }
        )
    }

    private suspend fun loadFeed(subscriptionsViewModel: SubscriptionsViewModel) {
        runSafely(
            onSuccess = { videos -> feed.updateIfChanged(videos) },
            ioBlock = { tryLoadFeed(subscriptionsViewModel) }
        )
    }

    private suspend fun loadBookmarks() {
        runSafely(
            onSuccess = { newBookmarks -> bookmarks.updateIfChanged(newBookmarks) },
            ioBlock = { DatabaseHolder.Database.playlistBookmarkDao().getAll() }
        )
    }

    private suspend fun loadPlaylists() {
        runSafely(
            onSuccess = { newPlaylists -> playlists.updateIfChanged(newPlaylists) },
            ioBlock = { PlaylistsHelper.getPlaylists() }
        )
    }

    private suspend fun loadVideosToContinueWatching() {
        if (!PlayerHelper.watchHistoryEnabled) return
        runSafely(
            onSuccess = { videos -> continueWatching.updateIfChanged(videos) },
            ioBlock = ::loadWatchingFromDB
        )
    }

    private suspend fun loadWatchingFromDB(): List<StreamItem> {
        val videos = DatabaseHelper.getWatchHistoryPage(1, 20)
        return DatabaseHelper.filterUnwatched(videos.map { it.toStreamItem() })
    }

    private suspend fun tryLoadFeed(subscriptionsViewModel: SubscriptionsViewModel): List<StreamItem> {
        val feed = subscriptionsViewModel.videoFeed.value ?: run {
            SubscriptionHelper.getFeed(forceRefresh = false).also {
                subscriptionsViewModel.videoFeed.postValue(it)
            }
        }
        return DatabaseHelper.filterByStreamTypeAndWatchPosition(feed, hideWatched, showUpcoming)
    }

    companion object {
        /** How long a feed request may run before the screen gives up. */
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val UNUSUAL_LOAD_TIME_MS = 10000L
        private const val FEATURED = "featured"
        private const val WATCHING = "watching"
        private const val TRENDING = "trending"
        private const val BOOKMARKS = "bookmarks"
        private const val PLAYLISTS = "playlists"

        // Category chip labels — includes Recently Uploaded and Live for India-first discovery.
        // The order is adaptive: "All" stays first; categories can be reordered by the
        // dynamic chip system based on user behavior and content availability.
        val CATEGORIES = listOf(
            "All", "Explore", "Gaming", "Music", "AI", "Android",
            "Tech", "Creative", "News", "Sports", "Live", "Recently Uploaded"
        )
    }

    /**
     * Keep the videos already on screen and append only the new ones, so a fetch
     * that finishes after the feed is visible cannot reorder or replace it.
     */
    private fun mergeWithVisible(previous: HomeFeedState?, incoming: HomeFeedState): HomeFeedState {
        val visible = previous?.items.orEmpty()
        if (visible.isEmpty()) return incoming

        val visibleIds = visible.mapNotNull { it.stableId() }.toSet()
        val additions = incoming.items.filter { item ->
            val id = item.stableId()
            id != null && id !in visibleIds
        }
        if (additions.isEmpty()) return incoming.copy(items = visible)
        return incoming.copy(items = visible + additions)
    }

    private fun com.atube.app.recommendation.models.HomeFeedItem.stableId(): String? = when (this) {
        is com.atube.app.recommendation.models.HomeFeedItem.Video -> data.url
        is com.atube.app.recommendation.models.HomeFeedItem.Skeleton -> id
        is com.atube.app.recommendation.models.HomeFeedItem.ShortsShelf -> videos.firstOrNull()?.url
        is com.atube.app.recommendation.models.HomeFeedItem.ContinueWatching -> "continue_watching"
        is com.atube.app.recommendation.models.HomeFeedItem.Loading -> "loading"
        is com.atube.app.recommendation.models.HomeFeedItem.AllCaughtUp -> "all_caught_up"
    }
}
