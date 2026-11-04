package com.atube.app.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.R
import com.atube.app.ui.dialogs.DownloadDialog
import com.atube.app.ui.dialogs.AddToPlaylistDialog
import com.atube.app.constants.IntentData
import androidx.core.os.bundleOf
import android.widget.Toast
import android.widget.PopupMenu
import android.content.Intent
import com.atube.app.recommendation.models.HomeFeedState
import com.atube.app.recommendation.models.HomeUiStatus
import com.atube.app.helpers.NetworkQuality
import com.atube.app.ui.activities.MainActivity
import com.atube.app.ui.adapters.HomeFeedAdapter
import com.atube.app.ui.models.HomeViewModel
import com.atube.app.helpers.StreamPrefetchManager
import com.atube.app.extensions.toID

/**
 * Home fragment with single primary RecyclerView for the continuous feed.
 *
 * Performance optimizations:
 * - RecyclerView itemAnimator disabled (returns false for all) to eliminate
 *   unnecessary layout passes during DiffUtil updates
 * - Stable IDs enabled for correct recycling
 * - ItemViewCacheSize increased for smoother scroll-back
 * - Scroll listener debounced to avoid spamming loadMore
 * - prefetchTopVideos runs only on first page, not every submitList
 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: com.atube.app.databinding.FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()

    private val homeFeedAdapter = HomeFeedAdapter(
        onItemClick = { video ->
            val videoId = video.url?.let { url ->
                if (url.startsWith("http")) url.substringAfter("v=").substringBefore("&")
                else url
            } ?: return@HomeFeedAdapter
            homeViewModel.recordClick(videoId)
        },
        onDismiss = { videoId -> removeFromFeed(videoId) },
        onMoreClick = { video, anchor -> showVideoMenu(video, anchor) }
    )

    private val categoryChipsAdapter = com.atube.app.ui.adapters.CategoryChipsAdapter(HomeViewModel.CATEGORIES) { category ->
        // where the user was in the category being left, kept for when they come back
        (binding.homeFeedRV.layoutManager as? LinearLayoutManager)?.let { lm ->
            homeViewModel.rememberScrollPosition(
                homeViewModel.selectedCategory.value ?: "All",
                lm.findFirstVisibleItemPosition()
            )
        }
        homeViewModel.loadCategory(requireContext(), category)
    }


    /** Debounce flag for pagination — prevents multiple loadMore calls during fast scroll */
    private var isLoadingMore = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = com.atube.app.databinding.FragmentHomeBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // Set up single primary RecyclerView with performance optimizations
        binding.homeFeedRV.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = homeFeedAdapter
            setHasFixedSize(true)

            // Increase off-screen cache for smoother scroll-back without re-binding
            setItemViewCacheSize(6)

            // Disable default change animations — they cause jank during DiffUtil updates
            // We keep add/remove animations for smooth pagination
            (itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.apply {
                changeDuration = 0
                moveDuration = 120
                addDuration = 120
                removeDuration = 80
                supportsChangeAnimations = false
            }

            // Never animate the initial data load
            val animator = itemAnimator
            itemAnimator = null

            // Allow smooth scrolling with fling optimization
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    // the header follows the feed: away on scroll down, back on scroll up
                    // The header and the category row stay where they are: they are
                    // not hidden on scroll any more.
                    if (isLoadingMore) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val totalItems = lm.itemCount
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (totalItems > 0 && lastVisible >= totalItems - 8) {
                        isLoadingMore = true
                        homeViewModel.loadMoreRecommendations(requireContext())
                    }
                }
            })
            itemAnimator = animator
        }

        // Set up category chips with smooth scrolling
        binding.categoryChips.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryChipsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(12)
            itemAnimator = null
        }

        // Swipe to refresh
        binding.refresh.setOnRefreshListener {
            showRefreshIndicator(true)
            homeViewModel.loadRecommendationFeed(requireContext(), forceRefresh = true)
        }

        // Observe feed state
        homeViewModel.feedState.observe(viewLifecycleOwner) { state ->
            showFeed(state)
            // Reset debounce flag when state changes
            if (state.status == HomeUiStatus.SUCCESS || state.status == HomeUiStatus.ERROR) {
                isLoadingMore = false
            }
        }

        // Retry button
        binding.refreshButton.setOnClickListener {
            homeViewModel.loadRecommendationFeed(requireContext(), forceRefresh = true)
        }

        // Only fetch when the view model has genuinely nothing. The adapter of a
        // freshly created fragment is always empty, so testing it meant every trip
        // back to Home started a new load: the list was replaced, the skeletons
        // came back and the thumbnails went grey until they downloaded again.
        // The observer below re-attaches the existing list instead.
        val current = homeViewModel.feedState.value
        val alreadyLoaded = current != null &&
            current.items.isNotEmpty() &&
            (current.status == HomeUiStatus.SUCCESS || current.status == HomeUiStatus.LOADING)
        if (!alreadyLoaded) {
            homeViewModel.loadRecommendationFeed(requireContext(), forceRefresh = false)
        }
    }

    /**
     * The three-dot menu on a feed card. Only options the app can actually carry
     * out are offered - the rest of YouTube's list needs a cast target or a
     * server-side account, which this app does not have.
     */
    /**
     * Recording a dismissal left the card on screen, so the menu item looked like it
     * did nothing. This also drops it from the list that is showing.
     *
     * It lives in a method rather than inline: the adapter is built with this lambda,
     * so referring to the adapter inside it is a forward reference.
     */
    /** Puts the list back where this category was scrolled to. */
    private var categoryRowHidden = false

    /** Slides the category chips away with the header, and back with it. */
    private fun moveCategoryRowWithHeader(hidden: Boolean) {
        if (hidden == categoryRowHidden) return
        categoryRowHidden = hidden
        val row = binding.categoryChips
        row.animate().cancel()
        row.animate()
            .translationY(if (hidden) -row.height.toFloat() else 0f)
            .alpha(if (hidden) 0f else 1f)
            .setDuration(if (hidden) 240L else 220L)
            .setInterpolator(
                if (hidden) android.view.animation.AccelerateInterpolator()
                else android.view.animation.DecelerateInterpolator()
            )
            .withEndAction { if (categoryRowHidden) row.translationY = 0f }
            .start()
    }

    private fun restoreScrollFor(category: String) {
        val position = homeViewModel.scrollPositionOf(category)
        if (position <= 0) return
        val recycler = binding.homeFeedRV
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun removeFromFeed(videoId: String) {
        homeViewModel.recordDismissal(videoId)
        val current: List<com.atube.app.recommendation.models.HomeFeedItem> =
            homeFeedAdapter.currentList
        val remaining = current.filterNot { item ->
            item is com.atube.app.recommendation.models.HomeFeedItem.Video &&
                item.data.url?.toID() == videoId
        }
        if (remaining.size != current.size) homeFeedAdapter.submitList(remaining)
    }

    /** Drops every card from a blocked channel, not only the one that was tapped. */
    private fun removeChannelFromFeed(channelId: String, videoId: String) {
        homeViewModel.recordDismissal(videoId)
        val current: List<com.atube.app.recommendation.models.HomeFeedItem> =
            homeFeedAdapter.currentList
        val remaining = current.filterNot { item ->
            item is com.atube.app.recommendation.models.HomeFeedItem.Video &&
                (item.data.uploaderUrl?.toID() == channelId || item.data.url?.toID() == videoId)
        }
        if (remaining.size != current.size) homeFeedAdapter.submitList(remaining)
    }

    /** Removes anything the user hid earlier, so it stays gone after a reload. */
    private fun filterHidden(
        items: List<com.atube.app.recommendation.models.HomeFeedItem>
    ): List<com.atube.app.recommendation.models.HomeFeedItem> {
        val feedback = com.atube.app.recommendation.feedback.RecommendationFeedbackManager
        if (feedback.getBlockedChannels().isEmpty() &&
            feedback.getNotInterestedVideos().isEmpty()
        ) return items

        return items.filterNot { item ->
            item is com.atube.app.recommendation.models.HomeFeedItem.Video && (
                feedback.isChannelBlocked(item.data.uploaderUrl?.toID().orEmpty()) ||
                    feedback.isNotInterested(item.data.url?.toID().orEmpty())
                )
        }
    }

    private fun showVideoMenu(video: com.atube.app.api.obj.StreamItem, anchor: View) {
        val videoId = video.url?.toID().orEmpty()
        if (videoId.isEmpty()) return

        com.atube.app.ui.views.VideoMenuPopup(anchor) { action ->
            when (action) {
                com.atube.app.ui.views.VideoMenuPopup.Action.PLAY_NEXT -> {
                    com.atube.app.util.PlayingQueue.addAsNext(video)
                    Toast.makeText(requireContext(), R.string.menu_queued_next, Toast.LENGTH_SHORT).show()
                }
                com.atube.app.ui.views.VideoMenuPopup.Action.PLAY_LAST -> {
                    com.atube.app.util.PlayingQueue.add(video)
                    Toast.makeText(requireContext(), R.string.menu_queued_last, Toast.LENGTH_SHORT).show()
                }
                com.atube.app.ui.views.VideoMenuPopup.Action.DOWNLOAD -> DownloadDialog().apply {
                    arguments = bundleOf(IntentData.videoId to videoId)
                }.show(childFragmentManager, DownloadDialog::class.java.name)

                com.atube.app.ui.views.VideoMenuPopup.Action.SAVE -> AddToPlaylistDialog().apply {
                    arguments = bundleOf(IntentData.videoInfo to video)
                }.show(childFragmentManager, AddToPlaylistDialog::class.java.name)

                com.atube.app.ui.views.VideoMenuPopup.Action.SHARE -> shareLink(videoId)

                com.atube.app.ui.views.VideoMenuPopup.Action.NOT_INTERESTED -> {
                    com.atube.app.recommendation.feedback.RecommendationFeedbackManager
                        .markNotInterested(videoId)
                    removeFromFeed(videoId)
                    Toast.makeText(requireContext(), R.string.menu_hidden, Toast.LENGTH_SHORT).show()
                }
                com.atube.app.ui.views.VideoMenuPopup.Action.DONT_RECOMMEND -> {
                    // block the whole channel, not just this card
                    val channelId = video.uploaderUrl?.toID().orEmpty()
                    com.atube.app.recommendation.feedback.RecommendationFeedbackManager
                        .blockChannel(channelId)
                    removeChannelFromFeed(channelId, videoId)
                    Toast.makeText(requireContext(), R.string.menu_channel_blocked, Toast.LENGTH_SHORT).show()
                }
                com.atube.app.ui.views.VideoMenuPopup.Action.REPORT -> shareLink(videoId)
            }
        }.show()
    }

    private fun shareLink(videoId: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
    }

    override fun onResume() {
        super.onResume()
        val currentState = homeViewModel.feedState.value
        if (currentState?.status == HomeUiStatus.INITIALIZING && homeFeedAdapter.itemCount == 0) {
            homeViewModel.loadRecommendationFeed(requireContext(), forceRefresh = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showFeed(state: HomeFeedState) {
        categoryChipsAdapter.setSelectedCategory(state.selectedCategory)

        // a category coming back from cache resumes where it was left, without a flash
        state.selectedCategory?.let { restoreScrollFor(it) }
        // the indicator belongs to the refresh gesture only
        showRefreshIndicator(state.status == HomeUiStatus.LOADING && state.isRefreshing)

        when (state.status) {
            HomeUiStatus.INITIALIZING -> {
                // A spinner is only for the case where there is genuinely nothing to
                // draw. If the feed already holds skeleton cards (or real videos), keep
                // them on screen: swapping them for a spinner is the flash this used to
                // show between the skeleton and the content.
                val hasContent = state.items.isNotEmpty()
                binding.progress.isVisible = hasContent.not()
                binding.homeFeedRV.isVisible = hasContent
                binding.nothingHere.isVisible = false
                binding.refresh.isRefreshing = false
                if (hasContent) homeFeedAdapter.submitList(filterHidden(state.items))
            }

            HomeUiStatus.LOADING -> {
                // the feed's own skeleton cards are the loading indicator, so no
                // spinner on top of them; only show it if there is nothing else
                binding.progress.isVisible = state.items.isEmpty()
                binding.nothingHere.isVisible = false
                binding.refresh.isRefreshing = state.isRefreshing

                if (state.items.isNotEmpty()) {
                    binding.homeFeedRV.isVisible = true
                    homeFeedAdapter.submitList(filterHidden(state.items))
                }
            }

            HomeUiStatus.SUCCESS -> {
                binding.progress.isVisible = false
                binding.refresh.isRefreshing = false
                binding.nothingHere.isVisible = false

                if (state.items.isNotEmpty()) {
                    binding.homeFeedRV.isVisible = true
                    // Use commitCallback to prefetch only after layout settles
                    homeFeedAdapter.submitList(filterHidden(state.items)) {
                        prefetchTopVideos(state.items)
                    }
                }
            }

            HomeUiStatus.EMPTY -> {
                binding.progress.isVisible = false
                binding.refresh.isRefreshing = false
                binding.changeInstance.isVisible = false
                showPlaceholderOrKeepFeed(getString(R.string.emptyList))
            }

            HomeUiStatus.ERROR -> {
                binding.progress.isVisible = false
                binding.refresh.isRefreshing = false
                binding.changeInstance.isVisible = false
                if (hasFeedContent()) {
                    // Videos are already on screen: a failed request must never
                    // throw them away and replace them with an error page. A link
                    // that is up but very slow is the usual cause, so say that
                    // instead - from the bottom of the screen.
                    binding.homeFeedRV.isVisible = true
                    binding.nothingHere.isVisible = false
                    (activity as? MainActivity)?.maybeWarnAboutLowNetwork()
                } else {
                    val reason = state.error?.message?.takeIf { it.isNotBlank() }
                    showPlaceholderOrKeepFeed(reason ?: getString(R.string.emptyList))
                }
            }

            HomeUiStatus.OFFLINE -> {
                binding.progress.isVisible = false
                binding.refresh.isRefreshing = false
                binding.changeInstance.isVisible = false
                showPlaceholderOrKeepFeed(getString(R.string.emptyList))
            }
        }
    }

    private fun showRefreshIndicator(show: Boolean) {
        binding.refreshIndicatorContainer.isVisible = show
    }

    private fun hasFeedContent(): Boolean = homeFeedAdapter.itemCount > 0

    /**
     * Shows the placeholder only when the feed is genuinely empty, and the Retry
     * button only when the device is offline. With a working connection a retry
     * just repeats the same failing request, which is what made this page feel like
     * it kept coming back on its own.
     */
    private fun showPlaceholderOrKeepFeed(message: String) {
        val hasContent = hasFeedContent()
        binding.nothingHere.isVisible = hasContent.not()
        binding.homeFeedRV.isVisible = hasContent

        if (hasContent) return

        binding.emptyText.text = message
        binding.refreshButton.isVisible = NetworkQuality.isOffline(requireContext())
    }

    private fun prefetchTopVideos(items: List<com.atube.app.recommendation.models.HomeFeedItem>) {
        val videoIds = items
            .filterIsInstance<com.atube.app.recommendation.models.HomeFeedItem.Video>()
            .take(3)
            .mapNotNull { it.data.url?.toID() }

        if (videoIds.isNotEmpty()) {
            StreamPrefetchManager.prefetchNext(videoIds)
        }
    }
}
