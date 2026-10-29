package com.atube.app.ui.adapters

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.api.obj.StreamItem
import com.atube.app.databinding.AllCaughtUpRowBinding
import com.atube.app.databinding.ItemSkeletonBinding
import com.atube.app.databinding.ShortsShelfBinding
import com.atube.app.databinding.TrendingRowBinding
import com.atube.app.extensions.toID
import com.atube.app.helpers.ImageHelper
import com.atube.app.helpers.NavigationHelper
import com.atube.app.ui.extensions.setFormattedDuration
import com.atube.app.ui.views.ShimmerHelper
import com.atube.app.parcelable.PlayerData
import com.atube.app.recommendation.models.HomeFeedItem

/**
 * Multi-view-type adapter for the Home recommendation feed.
 *
 * Performance optimizations:
 * - setHasStableIds(true) for correct RecyclerView recycling
 * - Touch listener set once in ViewHolder, not re-created on every bind
 * - Shelf adapters cached per ViewHolder (not re-created on rebind)
 * - View visibility toggled in bind, not recreated
 * - No buildString allocation on every bind — uses StringBuilder
 * - formatViews/formatTimeAgo cached as static utilities
 */
class HomeFeedAdapter(
    private val onItemClick: (StreamItem) -> Unit = {},
    private val onDismiss: (String) -> Unit = {}
) : ListAdapter<HomeFeedItem, RecyclerView.ViewHolder>(FeedDiffCallback) {

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_SHORTS_SHELF = 1
        private const val TYPE_CONTINUE_WATCHING = 2
        private const val TYPE_LOADING = 3
        private const val TYPE_ALL_CAUGHT_UP = 4
        private const val TYPE_SKELETON = 5

        fun formatViews(views: Long): String {
            return when {
                views >= 1_000_000 -> String.format("%.1fM views", views / 1_000_000.0)
                views >= 1_000 -> String.format("%.1fK views", views / 1_000.0)
                else -> "$views views"
            }
        }

        fun formatTimeAgo(timestamp: Long): String {
            val diffSec = (System.currentTimeMillis() - timestamp) / 1000
            val diffMin = diffSec / 60
            val diffHour = diffMin / 60
            val diffDay = diffHour / 24
            val diffWeek = diffDay / 7
            val diffMonth = diffDay / 30
            val diffYear = diffDay / 365
            return when {
                diffYear >= 1 -> String.format("%d year%s ago", diffYear, if (diffYear > 1) "s" else "")
                diffMonth >= 1 -> String.format("%d month%s ago", diffMonth, if (diffMonth > 1) "s" else "")
                diffWeek >= 1 -> String.format("%d week%s ago", diffWeek, if (diffWeek > 1) "s" else "")
                diffDay >= 1 -> String.format("%d day%s ago", diffDay, if (diffDay > 1) "s" else "")
                diffHour >= 1 -> String.format("%d hour%s ago", diffHour, if (diffHour > 1) "s" else "")
                diffMin >= 1 -> String.format("%d minute%s ago", diffMin, if (diffMin > 1) "s" else "")
                else -> "just now"
            }
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is HomeFeedItem.Video -> (item.data.url?.toID() ?: "").hashCode().toLong()
            is HomeFeedItem.ShortsShelf -> "shelf_${item.videos.firstOrNull()?.url?.toID() ?: ""}".hashCode().toLong()
            is HomeFeedItem.ContinueWatching -> "cw_${item.videos.firstOrNull()?.url?.toID() ?: ""}".hashCode().toLong()
            is HomeFeedItem.Loading -> -1L
            is HomeFeedItem.AllCaughtUp -> -2L
            is HomeFeedItem.Skeleton -> if (item.id.startsWith("skeleton_")) item.id.hashCode().toLong() else -3L
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is HomeFeedItem.Video -> TYPE_VIDEO
            is HomeFeedItem.ShortsShelf -> TYPE_SHORTS_SHELF
            is HomeFeedItem.ContinueWatching -> TYPE_CONTINUE_WATCHING
            is HomeFeedItem.Loading -> TYPE_LOADING
            is HomeFeedItem.AllCaughtUp -> TYPE_ALL_CAUGHT_UP
            is HomeFeedItem.Skeleton -> TYPE_SKELETON
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_VIDEO -> VideoViewHolder(TrendingRowBinding.inflate(inflater, parent, false))
            TYPE_SHORTS_SHELF -> ShortsShelfViewHolder(ShortsShelfBinding.inflate(inflater, parent, false))
            TYPE_CONTINUE_WATCHING -> ContinueWatchingViewHolder(ShortsShelfBinding.inflate(inflater, parent, false))
            TYPE_LOADING -> LoadingViewHolder(AllCaughtUpRowBinding.inflate(inflater, parent, false))
            TYPE_ALL_CAUGHT_UP -> AllCaughtUpViewHolder(AllCaughtUpRowBinding.inflate(inflater, parent, false))
            TYPE_SKELETON -> SkeletonViewHolder(ItemSkeletonBinding.inflate(inflater, parent, false))
            else -> VideoViewHolder(TrendingRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeFeedItem.Video -> bindVideo(holder, item)
            is HomeFeedItem.ShortsShelf -> bindShortsShelf(holder, item)
            is HomeFeedItem.ContinueWatching -> bindContinueWatching(holder, item)
            is HomeFeedItem.Loading -> {}
            is HomeFeedItem.AllCaughtUp -> {}
            is HomeFeedItem.Skeleton -> (holder as SkeletonViewHolder).bind()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // Cancel any pending image loads when the view is recycled
        when (holder) {
            is VideoViewHolder -> {
                holder.binding.thumbnail.setImageDrawable(null)
                // do not leave a shimmer animating on a recycled row
                ShimmerHelper.stopShimmer(holder.binding.thumbnail)
            }
            is SkeletonViewHolder -> {
                // stop the shimmer, otherwise the animation keeps running off screen
                holder.stop()
            }
        }
    }

    private fun bindVideo(holder: RecyclerView.ViewHolder, item: HomeFeedItem.Video) {
        val binding = (holder as VideoViewHolder).binding
        val video = item.data
        val context = binding.root.context

        binding.apply {
            textViewTitle.text = video.title

            // Build metadata string efficiently without buildString allocation
            val sb = StringBuilder(64)
            sb.append(video.uploaderName ?: "")
            video.views?.let { views ->
                if (views > 0) {
                    sb.append(" • ")
                    sb.append(formatViews(views))
                }
            }
            if (video.uploaded > 0) {
                sb.append(" • ")
                sb.append(formatTimeAgo(video.uploaded))
            }
            textViewChannel.text = sb.toString()

            video.duration?.let { thumbnailDuration.setFormattedDuration(it, video.isShort, video.uploaded) }

            // Thumbnail: the skeleton keeps shimmering until the image is really
            // there, then Coil crossfades it in over the placeholder and the
            // shimmer is dropped. A thumbnail that never arrives keeps its skeleton.
            ShimmerHelper.applyShimmer(thumbnail, cornerRadiusDp = 12f)
            ImageHelper.loadImageHighQuality(video.thumbnail, thumbnail) { loaded ->
                if (loaded) ShimmerHelper.stopShimmer(thumbnail)
            }

            // Load channel avatar if available
            if (!video.uploaderAvatar.isNullOrEmpty()) {
                channelImageContainer.visibility = android.view.View.VISIBLE
                ImageHelper.loadImage(video.uploaderAvatar, channelImage)
            } else {
                channelImageContainer.visibility = android.view.View.GONE
            }

            // Set up touch listener once in ViewHolder init, not here
            // The click handler is set here because it needs the video reference
            root.setOnClickListener {
                val videoId = video.url?.toID() ?: return@setOnClickListener
                onItemClick(video)
                NavigationHelper.navigateVideo(context, PlayerData(videoId))
            }
        }
    }

    private fun bindShortsShelf(holder: RecyclerView.ViewHolder, item: HomeFeedItem.ShortsShelf) {
        val binding = (holder as ShortsShelfViewHolder).binding
        val context = binding.root.context

        // Reuse cached shelf adapter if possible
        if (binding.shelfRecyclerView.adapter == null) {
            binding.shelfRecyclerView.layoutManager = LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false
            )
            binding.shelfRecyclerView.adapter = VideoCardsAdapter(columnWidthDp = 120f)
            binding.shelfRecyclerView.setHasFixedSize(true)
            binding.shelfRecyclerView.setItemViewCacheSize(4)
        }
        (binding.shelfRecyclerView.adapter as? VideoCardsAdapter)?.submitList(item.videos)
    }

    private fun bindContinueWatching(holder: RecyclerView.ViewHolder, item: HomeFeedItem.ContinueWatching) {
        val binding = (holder as ContinueWatchingViewHolder).binding
        val context = binding.root.context

        if (binding.shelfRecyclerView.adapter == null) {
            binding.shelfRecyclerView.layoutManager = LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false
            )
            binding.shelfRecyclerView.adapter = VideoCardsAdapter(columnWidthDp = 160f)
            binding.shelfRecyclerView.setHasFixedSize(true)
            binding.shelfRecyclerView.setItemViewCacheSize(4)
        }
        (binding.shelfRecyclerView.adapter as? VideoCardsAdapter)?.submitList(item.videos)
    }

    // === ViewHolders ===

    class VideoViewHolder(val binding: TrendingRowBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // Set up press animation once — not on every bind
            binding.root.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(60)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                }
                false
            }
        }
    }
    class ShortsShelfViewHolder(val binding: ShortsShelfBinding) : RecyclerView.ViewHolder(binding.root)
    class ContinueWatchingViewHolder(val binding: ShortsShelfBinding) : RecyclerView.ViewHolder(binding.root)
    class LoadingViewHolder(binding: AllCaughtUpRowBinding) : RecyclerView.ViewHolder(binding.root)
    class AllCaughtUpViewHolder(binding: AllCaughtUpRowBinding) : RecyclerView.ViewHolder(binding.root)
    /**
     * The loading placeholder: YouTube-style grey blocks with the shimmer from
     * [ShimmerHelper] running over them.
     */
    class SkeletonViewHolder(val binding: ItemSkeletonBinding) : RecyclerView.ViewHolder(binding.root) {
        private val placeholders = listOf(
            binding.skeletonThumbnail,
            binding.skeletonAvatar,
            binding.skeletonTitle1,
            binding.skeletonTitle2,
            binding.skeletonChannel
        )

        fun bind() {
            placeholders.forEach { ShimmerHelper.applyShimmer(it) }
        }

        fun stop() {
            placeholders.forEach { ShimmerHelper.stopShimmer(it) }
        }
    }

    // === DiffUtil ===

    object FeedDiffCallback : DiffUtil.ItemCallback<HomeFeedItem>() {
        override fun areItemsTheSame(oldItem: HomeFeedItem, newItem: HomeFeedItem): Boolean {
            return when {
                oldItem is HomeFeedItem.Video && newItem is HomeFeedItem.Video ->
                    oldItem.data.url == newItem.data.url
                oldItem is HomeFeedItem.ShortsShelf && newItem is HomeFeedItem.ShortsShelf ->
                    oldItem.videos.firstOrNull()?.url == newItem.videos.firstOrNull()?.url
                oldItem is HomeFeedItem.ContinueWatching && newItem is HomeFeedItem.ContinueWatching ->
                    oldItem.videos.firstOrNull()?.url == newItem.videos.firstOrNull()?.url
                oldItem is HomeFeedItem.Loading && newItem is HomeFeedItem.Loading -> true
                oldItem is HomeFeedItem.AllCaughtUp && newItem is HomeFeedItem.AllCaughtUp -> true
                oldItem is HomeFeedItem.Skeleton && newItem is HomeFeedItem.Skeleton -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: HomeFeedItem, newItem: HomeFeedItem): Boolean {
            return oldItem == newItem
        }
    }
}
