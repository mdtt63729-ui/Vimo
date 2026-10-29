package com.atube.app.recommendation

import com.atube.app.api.SubscriptionHelper
import com.atube.app.db.DatabaseHelper
import com.atube.app.db.DatabaseHolder
import com.atube.app.extensions.toID
import com.atube.app.helpers.PlayerHelper
import com.atube.app.recommendation.candidate.CandidateCoordinator
import com.atube.app.recommendation.feedback.RecommendationFeedbackManager
import com.atube.app.recommendation.models.BehaviorEvent
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.FeedSession
import com.atube.app.recommendation.models.HomeFeedItem
import com.atube.app.recommendation.models.HomeFeedState
import com.atube.app.recommendation.models.HomeUiStatus
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.RecommendationWeights
import com.atube.app.recommendation.models.TopicAffinity
import com.atube.app.recommendation.models.UserBehaviorSignal
import com.atube.app.recommendation.models.UserInterestProfile
import com.atube.app.recommendation.ranking.DiversityEngine
import com.atube.app.recommendation.ranking.RecommendationEngine
import com.atube.app.recommendation.ranking.UserInterestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Orchestrates the full recommendation pipeline:
 *
 *   Candidate Generation -> Deduplication -> Hard Filtering ->
 *   Personalization -> Scoring -> Exploration Mix ->
 *   Diversification -> Final Feed Page
 *
 * This is the single entry point for the Home feed.
 * It manages sessions, caching, pagination, and prefetching.
 *
 * Key design:
 * - Mutex protects pagination from concurrent loadMore() calls
 * - Candidate pool grows continuously through provider rotation
 * - No feed jumping: new items are appended, never replacing existing ones
 * - Ranking happens on Dispatchers.Default (off main thread)
 */
class HomeFeedCoordinator {

    private val candidateCoordinator = CandidateCoordinator()
    private val recommendationEngine = RecommendationEngine()
    private val diversityEngine = DiversityEngine()
    private val userInterestEngine = UserInterestEngine()

    // In-memory cache
    private var cachedFeed: List<HomeFeedItem>? = null
    private var cacheTimestamp: Long = 0L

    // Current session
    private var currentSession: FeedSession? = null

    // Pagination state
    private val paginationMutex = Mutex()
    private var isFetchingMore = false

    companion object {
        private const val CACHE_FRESH_MS = 10 * 60 * 1000L
        private const val SHORTS_INTERVAL = 6
        private const val SHORTS_SHELF_SIZE = 6
        private const val TARGET_CANDIDATE_POOL = 500
        private const val PREFETCH_THRESHOLD = 0.65 // 65% scrolled -> prefetch
    }

    /**
     * Load the initial feed page.
     * Shows cached feed immediately if available, then fetches fresh.
     */
    suspend fun loadInitialFeed(
        region: String,
        forceRefresh: Boolean
    ): HomeFeedState = withContext(Dispatchers.IO) {
        // Create or refresh session
        val session = if (forceRefresh || currentSession == null) {
            candidateCoordinator.reset()
            userInterestEngine.resetSession()
            FeedSession(category = "All", region = region).also {
                currentSession = it
            }
        } else {
            currentSession!!.also { it.newSeed() }
        }

        // Build user profile from local data
        buildUserProfile()
        userInterestEngine.applyDecay()

        // Build recommendation context
        val context = buildContext(session, region)

        // Fetch candidates from all sources concurrently
        val candidatePage = candidateCoordinator.fetchCandidates(context, 0)

        // Separate Shorts from normal videos
        val (normalCandidates, shortsCandidates) = partitionByShorts(candidatePage.candidates)

        // Rank on background dispatcher (not main thread)
        val ranked = withContext(Dispatchers.Default) {
            recommendationEngine.rank(normalCandidates, context, userInterestEngine.getCombinedProfile())
        }

        // Apply diversity filter
        val diversified = diversityEngine.diversify(ranked, userInterestEngine.getCombinedProfile())

        // Convert to feed items with Shorts shelves inserted
        val feedItems = buildFeedItems(diversified, shortsCandidates, session)

        // Update session with shown IDs
        feedItems.forEach { item ->
            if (item is HomeFeedItem.Video) {
                session.markShown(item.data.url?.toID() ?: "")
            }
        }

        // Cache the result
        cachedFeed = feedItems
        cacheTimestamp = System.currentTimeMillis()

        HomeFeedState(
            items = feedItems,
            // Always carry a real status: the default is INITIALIZING, and a caller
            // that posts the state as-is would drop the UI back to the spinner and
            // hide the feed for a moment.
            status = if (feedItems.isEmpty()) HomeUiStatus.EMPTY else HomeUiStatus.SUCCESS,
            isLoadingMore = false,
            hasMore = candidatePage.hasMore,
            selectedCategory = session.category,
            sessionId = session.id
        )
    }

    /**
     * Load the next page of the feed (pagination).
     * Uses Mutex to prevent concurrent loadMore() calls.
     * Appends new items — NEVER replaces existing ones.
     */
    suspend fun loadMore(region: String): HomeFeedState? = withContext(Dispatchers.IO) {
        val session = currentSession ?: return@withContext null
        if (!session.hasMore) return@withContext null

        // Mutex — only one pagination request at a time
        paginationMutex.withLock {
            if (isFetchingMore) return@withContext null
            isFetchingMore = true
        }

        try {
            session.pageIndex++

            val context = buildContext(session, region)
            val candidatePage = candidateCoordinator.fetchCandidates(context, session.pageIndex)

            if (candidatePage.candidates.isEmpty()) {
                // All sources exhausted
                session.hasMore = false
                val allCaughtUp = (cachedFeed ?: emptyList()) + HomeFeedItem.AllCaughtUp()
                cachedFeed = allCaughtUp
                return@withContext HomeFeedState(
                    items = allCaughtUp,
                    status = HomeUiStatus.SUCCESS,
                    isLoadingMore = false,
                    hasMore = false,
                    selectedCategory = session.category,
                    sessionId = session.id
                )
            }

            val (normalCandidates, shortsCandidates) = partitionByShorts(candidatePage.candidates)

            // Rank on background dispatcher
            val ranked = withContext(Dispatchers.Default) {
                recommendationEngine.rank(normalCandidates, context, userInterestEngine.getCombinedProfile())
            }
            val diversified = diversityEngine.diversify(ranked, userInterestEngine.getCombinedProfile())

            // Build new items — APPEND only, never replace
            val existingFeed = cachedFeed?.filter { it !is HomeFeedItem.AllCaughtUp && it !is HomeFeedItem.Loading } ?: emptyList()

            val newItems = diversified.mapIndexed { index, candidate ->
                val videoId = candidate.videoId
                session.markShown(videoId)
                HomeFeedItem.Video(
                    data = candidate.video,
                    source = candidate.source.name,
                    position = existingFeed.size + index
                )
            }

            // Also build Shorts shelves from new shorts candidates
            val newShortsShelves = buildShortsShelves(shortsCandidates, existingFeed.size)

            val updatedFeed = existingFeed + newItems + newShortsShelves
            cachedFeed = updatedFeed

            HomeFeedState(
                items = updatedFeed,
                status = HomeUiStatus.SUCCESS,
                isLoadingMore = false,
                hasMore = candidatePage.hasMore,
                selectedCategory = session.category,
                sessionId = session.id
            )
        } finally {
            isFetchingMore = false
        }
    }

    /**
     * Load feed for a specific category.
     * Cancels any in-progress loading and starts fresh.
     */
    suspend fun loadCategory(category: String, region: String): HomeFeedState = withContext(Dispatchers.IO) {
        // Cancel any in-progress pagination
        isFetchingMore = false

        val session = FeedSession(category = category, region = region)
        currentSession = session
        candidateCoordinator.reset()

        val weights = when (category) {
            "All" -> RecommendationWeights.DEFAULT
            "Explore" -> RecommendationWeights(
                freshness = 0.20, popularity = 0.20, trending = 0.25,
                explorationFactor = 0.20, subscription = 0.05
            )
            "Recently Uploaded" -> RecommendationWeights.DEFAULT.copy(
                freshness = 0.40, popularity = 0.10, trending = 0.05,
                categoryMatch = 0.10, explorationFactor = 0.15
            )
            "Live" -> RecommendationWeights.DEFAULT.copy(
                trending = 0.35, freshness = 0.30, popularity = 0.15,
                categoryMatch = 0.25
            )
            "Creative" -> RecommendationWeights.DEFAULT.copy(categoryMatch = 0.35)
            else -> RecommendationWeights.DEFAULT.copy(categoryMatch = 0.30)
        }

        val context = buildContext(session, region).copy(weights = weights)
        val candidatePage = candidateCoordinator.fetchCandidates(context, 0)
        val (normalCandidates, shortsCandidates) = partitionByShorts(candidatePage.candidates)

        val ranked = withContext(Dispatchers.Default) {
            recommendationEngine.rank(normalCandidates, context, userInterestEngine.getCombinedProfile())
        }
        val diversified = diversityEngine.diversify(ranked, userInterestEngine.getCombinedProfile())
        val feedItems = buildFeedItems(diversified, shortsCandidates, session)

        feedItems.forEach { item ->
            if (item is HomeFeedItem.Video) {
                session.markShown(item.data.url?.toID() ?: "")
            }
        }

        cachedFeed = feedItems
        cacheTimestamp = System.currentTimeMillis()

        HomeFeedState(
            items = feedItems,
            status = if (feedItems.isEmpty()) HomeUiStatus.EMPTY else HomeUiStatus.SUCCESS,
            hasMore = candidatePage.hasMore,
            selectedCategory = category,
            sessionId = session.id
        )
    }

    /** Get cached feed for instant display. */
    fun getCachedFeed(): List<HomeFeedItem>? = cachedFeed

    /** Record a click. */
    fun recordClick(videoId: String) {
        currentSession?.markClicked(videoId)
        val item = findVideoInCache(videoId)
        if (item != null) {
            val topic = extractTopic(item)
            val channelId = item.uploaderUrl?.toID()
            userInterestEngine.recordSignal(
                UserBehaviorSignal(
                    event = BehaviorEvent.CLICK,
                    videoId = videoId,
                    channelId = channelId,
                    topic = topic
                )
            )
        }
    }

    /** Record a dismissal. */
    fun recordDismissal(videoId: String) {
        currentSession?.markDismissed(videoId)
        RecommendationFeedbackManager.dismissVideo(videoId)
        userInterestEngine.recordSignal(
            UserBehaviorSignal(
                event = BehaviorEvent.NOT_INTERESTED,
                videoId = videoId
            )
        )
    }

    /** Record a watch signal for learning. */
    fun recordWatchSignal(
        videoId: String,
        channelId: String?,
        watchDuration: Long,
        videoDuration: Long,
        completionRatio: Float
    ) {
        val event = if (completionRatio >= 0.90f) BehaviorEvent.WATCH_COMPLETE
                     else BehaviorEvent.WATCH_PROGRESS

        val topic = findVideoInCache(videoId)?.let { extractTopic(it) }

        userInterestEngine.recordSignal(
            UserBehaviorSignal(
                event = event,
                videoId = videoId,
                channelId = channelId,
                topic = topic,
                watchDuration = watchDuration,
                videoDuration = videoDuration,
                completionRatio = completionRatio
            )
        )

        // Also boost/penalize channel in combined profile
        if (completionRatio > 0.6f && channelId != null) {
            userInterestEngine.getSessionProfile().boostChannel(channelId, 0.15f)
        } else if (completionRatio < 0.1f && channelId != null) {
            userInterestEngine.getSessionProfile().penalizeChannel(channelId, 0.05f)
        }
    }

    /** Get the current user interest profile (combined session + long-term). */
    fun getUserProfile(): UserInterestProfile = userInterestEngine.getCombinedProfile()

    // === Private helpers ===

    private suspend fun buildUserProfile() {
        // Build long-term profile from watch history
        try {
            if (PlayerHelper.watchHistoryEnabled) {
                val history = DatabaseHelper.getWatchHistoryPage(1, 50)
                for (item in history) {
                    val channelId = item.uploaderUrl?.toID()
                    if (channelId != null) {
                        userInterestEngine.getLongTermProfile().boostChannel(channelId, 0.05f)
                    }
                    val title = item.title?.lowercase() ?: ""
                    extractTopics(title).forEach { topic ->
                        userInterestEngine.getLongTermProfile().boostTopic(topic, 0.03f)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Build from search history
        try {
            val searchHistory = DatabaseHolder.Database.searchHistoryDao().getAll()
            for (item in searchHistory.take(10)) {
                val query = item.query.lowercase()
                extractTopics(query).forEach { topic ->
                    userInterestEngine.getLongTermProfile().boostTopic(topic, 0.08f)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private suspend fun buildContext(session: FeedSession, region: String): RecommendationContext {
        val subscribedChannelIds = try {
            SubscriptionHelper.getSubscriptionChannelIds().toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val watchedIds = try {
            DatabaseHelper.getWatchHistoryPage(1, 100).mapNotNull { it.videoId }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val searchHistory = try {
            DatabaseHolder.Database.searchHistoryDao().getAll().map { it.query }
        } catch (e: Exception) {
            emptyList()
        }

        val recentlyWatched = try {
            DatabaseHelper.getWatchHistoryPage(1, 5).map { it.toStreamItem() }
        } catch (e: Exception) {
            emptyList()
        }

        return RecommendationContext(
            sessionId = session.id,
            category = session.category,
            region = region,
            displayedIds = session.shownIds.toSet(),
            dismissedVideoIds = session.dismissedIds.toSet(),
            blockedChannelIds = RecommendationFeedbackManager.getBlockedChannels(),
            watchedVideoIds = watchedIds,
            recentlyWatched = recentlyWatched,
            searchHistory = searchHistory,
            subscribedChannelIds = subscribedChannelIds,
            weights = RecommendationWeights.DEFAULT,
            pageIndex = session.pageIndex,
            sessionSeed = session.sessionSeed
        )
    }

    private fun partitionByShorts(
        candidates: List<RecommendationCandidate>
    ): Pair<List<RecommendationCandidate>, List<RecommendationCandidate>> {
        val normal = candidates.filter { !it.video.isShort }
        val shorts = candidates.filter { it.video.isShort }
        return normal to shorts
    }

    private fun buildFeedItems(
        normalCandidates: List<RecommendationCandidate>,
        shortsCandidates: List<RecommendationCandidate>,
        session: FeedSession
    ): List<HomeFeedItem> {
        val items = mutableListOf<HomeFeedItem>()

        // Continue watching shelf
        val continueWatching = normalCandidates
            .filter { it.source == CandidateSource.HISTORY_AFFINITY }
            .take(5)
            .map { it.video }
        if (continueWatching.isNotEmpty()) {
            items.add(HomeFeedItem.ContinueWatching(continueWatching))
        }

        // Normal videos
        val normalVideos = normalCandidates
            .filter { it.source != CandidateSource.HISTORY_AFFINITY }
            .mapIndexed { index, candidate ->
                HomeFeedItem.Video(
                    data = candidate.video,
                    source = candidate.source.name,
                    position = index
                )
            }

        // Insert Shorts shelves every SHORTS_INTERVAL videos
        var videoCount = 0
        var shortIndex = 0
        for (videoItem in normalVideos) {
            items.add(videoItem)
            videoCount++

            if (videoCount % SHORTS_INTERVAL == 0 && shortIndex < shortsCandidates.size) {
                val shelfVideos = shortsCandidates
                    .subList(shortIndex, minOf(shortIndex + SHORTS_SHELF_SIZE, shortsCandidates.size))
                    .map { it.video }
                items.add(HomeFeedItem.ShortsShelf(shelfVideos))
                shortIndex += SHORTS_SHELF_SIZE
            }
        }

        return items
    }

    private fun buildShortsShelves(
        shortsCandidates: List<RecommendationCandidate>,
        basePosition: Int
    ): List<HomeFeedItem> {
        if (shortsCandidates.isEmpty()) return emptyList()
        return listOf(HomeFeedItem.ShortsShelf(shortsCandidates.take(SHORTS_SHELF_SIZE).map { it.video }))
    }

    private fun findVideoInCache(videoId: String) = try {
        cachedFeed
            ?.filterIsInstance<HomeFeedItem.Video>()
            ?.firstOrNull { it.data.url?.toID() == videoId }
            ?.data
    } catch (e: Exception) { null }

    private fun extractTopic(video: com.atube.app.api.obj.StreamItem): String? {
        val title = video.title?.lowercase() ?: return null
        return extractTopics(title).firstOrNull()
    }

    private fun extractTopics(text: String): List<String> {
        val stopWords = setOf("the", "and", "for", "with", "from", "this", "that", "your",
            "you", "are", "not", "but", "how", "what", "why", "when", "best", "top",
            "new", "latest", "video", "watch", "full", "hd", "4k", "2024", "2025", "2026")
        return text.split(" ", "\n", ".", ",", "!", "?")
            .filter { it.length > 3 && it.lowercase() !in stopWords }
            .map { word ->
                // Map to cluster if possible
                TopicAffinity.clusterOf(word) ?: word.lowercase()
            }
            .distinct()
            .take(5)
    }
}
