package com.atube.app.ui.adapters

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.obj.StreamItem
import com.atube.app.constants.PreferenceKeys
import com.atube.app.databinding.ItemShortBinding
import com.atube.app.extensions.toID
import com.atube.app.helpers.DynamicThemeManager
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.ui.extensions.setupSubscriptionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for the Shorts ViewPager2.
 *
 * Uses a single shared ExoPlayer managed by [ShortsPlayerManager] to prevent
 * memory leaks and ensure only one player is active at a time.
 *
 * Features:
 * - Single active player (released when leaving Shorts)
 * - Play/Pause button with animation
 * - Tap-to-pause/play on video area
 * - Mute/unmute with persisted preference
 * - Buffering indicator
 * - Auto-play on page selected
 * - Pause on page detached
 * - Stream failure -> skip to next
 */
class ShortsPagerAdapter(
    private val onShareClick: (StreamItem) -> Unit,
    private val onDownloadClick: (String) -> Unit,
    private val onCommentClick: (String) -> Unit,
    private val onChannelClick: (String?) -> Unit,
    private val onSkipToNext: () -> Unit = {},
) : ListAdapter<StreamItem, ShortsPagerAdapter.ShortsViewHolder>(DIFF_CALLBACK) {

    private val playerManager = ShortsPlayerManager()
    private var currentPosition = 0
    private var shouldResumeAfterLifecyclePause = false
    private var isMuted = PreferenceHelper.getBoolean(
        PreferenceKeys.SHORTS_MUTED, false
    )

    /** Preload cache for next Short's stream URL — bounded to last 5 entries. */
    private val streamCache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
            return size > 5
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortsViewHolder {
        val binding = ItemShortBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ShortsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShortsViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onViewDetachedFromWindow(holder: ShortsViewHolder) {
        super.onViewDetachedFromWindow(holder)
        // Pause but keep the last frame visible — do NOT release player
        holder.pause()
    }

    /**
     * Called when a page becomes selected in ViewPager2.
     * Plays the video at this position and pauses all others.
     */
    fun onPageSelected(position: Int) {
        if (position == RecyclerView.NO_POSITION || position !in 0 until itemCount) return
        if (currentPosition != position) playerManager.pause(currentPosition)
        currentPosition = position
        // Only the selected page is allowed to create/attach a player. ViewPager2
        // binds adjacent pages for prefetch, so starting playback from onBind would
        // otherwise allow an off-screen page to replace the active player.
        notifyItemChanged(position)

        // Preload the next two Shorts' stream URLs without creating players.
        preloadNext(position + 1)
        preloadNext(position + 2)
    }

    /**
     * Preload the stream URL for the next Short so playback starts instantly.
     */
    private fun preloadNext(nextPosition: Int) {
        if (nextPosition >= itemCount) return
        val nextItem = getItem(nextPosition)
        val videoId = nextItem.url?.toID() ?: return
        if (videoId in streamCache) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val streams = MediaServiceRepository.instance.getStreams(videoId)
                // For preload: prefer HLS (single URL, audio+video combined)
                // This is the fastest path — no MergingMediaSource needed
                val url = streams.hls
                    ?: streams.videoStreams
                        .filter { it.url != null && it.videoOnly != true }
                        .maxByOrNull { it.height ?: 0 }?.url
                    ?: streams.videoStreams
                        .filter { it.url != null }
                        .maxByOrNull { it.height ?: 0 }?.url
                if (url != null) {
                    streamCache[videoId] = url
                }
            } catch (e: Exception) {
                // Preload failed — will resolve on demand
            }
        }
    }

    fun releasePlayer() {
        playerManager.release()
    }

    fun pauseCurrentPlayer() {
        shouldResumeAfterLifecyclePause = playerManager.isPlaying()
        playerManager.pause(currentPosition)
    }

    fun resumeCurrentPlayer() {
        if (shouldResumeAfterLifecyclePause) {
            playerManager.resume(currentPosition)
            shouldResumeAfterLifecyclePause = false
        }
    }

    inner class ShortsViewHolder(
        private val binding: ItemShortBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
        private var isPlaying = false

        fun bind(item: StreamItem, position: Int) {
            binding.shortTitle.text = item.title ?: ""
            binding.shortChannelName.text = item.uploaderName ?: ""
            binding.shortDescription.text = item.shortDescription.orEmpty()
            binding.shortDescription.visibility =
                if (item.shortDescription.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.shortDescription.setOnClickListener {
                val expanded = binding.shortDescription.maxLines > 2
                binding.shortDescription.maxLines = if (expanded) 2 else Int.MAX_VALUE
            }

            // Apply dynamic video theme from thumbnail
            if (DynamicThemeManager.isEnabled() && item.thumbnail != null) {
                DynamicThemeManager.applyToView(binding.root.context, item.thumbnail) { colors ->
                    // Tint the gradient overlay with the dominant color
                    binding.root.background?.let { bg ->
                        // Apply a subtle color tint to the background
                        binding.root.setBackgroundColor(colors.primaryDark)
                    }
                }
            }

            // Load channel avatar
            item.uploaderAvatar?.let { avatarUrl ->
                binding.shortChannelAvatar.load(avatarUrl) { crossfade(true) }
            }

            // The provider exposes view counts, not like/comment counts. Keep
            // these action labels empty rather than presenting unrelated values.
            binding.shortLikeCount.text = ""
            binding.shortCommentCount.text = ""

            // Adjacent pages are bound by ViewPager2 for prefetch. Resolve and
            // prepare a player only for the currently selected page.
            val videoId = item.url ?: return
            if (position == currentPosition) loadAndPlay(videoId, position)

            // === Play/Pause overlay ===
            setupButtonPressAnimation(binding.shortPlayPauseBtn)
            binding.shortPlayPauseBtn.setOnClickListener {
                togglePlayPause()
            }

            // === Tap-to-pause/play + double-tap-to-like on video area ===
            val gestureDetector = GestureDetector(
                binding.root.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        togglePlayPause()
                        return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean = true
                }
            )
            binding.shortPlayerView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }

            // === Mute/Unmute ===
            // Set initial mute icon based on persisted preference
            binding.shortMuteBtn.setImageResource(
                if (isMuted) com.atube.app.R.drawable.ic_mute
                else com.atube.app.R.drawable.ic_volume
            )
            binding.shortMuteBtn.setOnClickListener {
                toggleMute()
            }

            // === Action buttons with smooth press-scale animation ===
            setupButtonPressAnimation(binding.shortLikeBtn)
            binding.shortLikeBtn.setOnClickListener {
                // Current read-only video providers expose no like-write endpoint.
                // Do not display a successful state for an action that was not saved.
                android.widget.Toast.makeText(
                    binding.root.context,
                    com.atube.app.R.string.shorts_like_unavailable,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            setupButtonPressAnimation(binding.shortShareBtn)
            binding.shortShareBtn.setOnClickListener { onShareClick(item) }
            setupButtonPressAnimation(binding.shortDownloadBtn)
            binding.shortDownloadBtn.setOnClickListener { onDownloadClick(videoId) }
            setupButtonPressAnimation(binding.shortCommentBtn)
            binding.shortCommentBtn.setOnClickListener { onCommentClick(videoId) }
            setupButtonPressAnimation(binding.shortMuteBtn)

            // Channel avatar click
            setupButtonPressAnimation(binding.shortChannelAvatar)
            binding.shortChannelAvatar.setOnClickListener {
                onChannelClick(item.uploaderUrl)
            }

            // Persist channel subscriptions through Vimo's existing local
            // SubscriptionHelper instead of changing the label only in memory.
            binding.shortSubscribeBtn.setupSubscriptionButton(
                channelId = item.uploaderUrl?.toID(),
                channelName = item.uploaderName.orEmpty(),
                channelAvatar = item.uploaderAvatar,
                channelVerified = item.uploaderVerified == true
            )
        }

        private fun togglePlayPause() {
            if (isPlaying) {
                playerManager.pause(bindingAdapterPosition)
                isPlaying = false
                binding.shortPlayPauseBtn.setImageResource(com.atube.app.R.drawable.ic_play)
                showPlayPauseAnimation(false)
            } else {
                playerManager.resume(bindingAdapterPosition)
                isPlaying = true
                binding.shortPlayPauseBtn.setImageResource(com.atube.app.R.drawable.ic_pause)
                showPlayPauseAnimation(true)
            }
        }

        /**
         * Apply a smooth press-scale animation to a button:
         * On press: scale down to 0.85 quickly
         * On release: spring back to 1.0 with overshoot
         */
        private fun setupButtonPressAnimation(view: View) {
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.85f)
                            .scaleY(0.85f)
                            .setDuration(60)
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .withEndAction {
                                // Subtle overshoot bounce
                                v.animate()
                                    .scaleX(1.08f)
                                    .scaleY(1.08f)
                                    .setDuration(80)
                                    .withEndAction {
                                        v.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(60)
                                            .start()
                                    }
                                    .start()
                            }
                            .start()
                    }
                }
                false // Allow click listener to fire
            }
        }

        private fun toggleMute() {
            isMuted = !isMuted
            playerManager.setMuted(isMuted)
            // Persist mute preference
            PreferenceHelper.putBoolean(PreferenceKeys.SHORTS_MUTED, isMuted)
            binding.shortMuteBtn.setImageResource(
                if (isMuted) com.atube.app.R.drawable.ic_mute
                else com.atube.app.R.drawable.ic_volume
            )
        }

        private fun showPlayPauseAnimation(isPlaying: Boolean) {
            // Smooth scale + alpha animation via ViewPropertyAnimator
            // Briefly show the icon large and opaque, then fade+shrink back
            binding.shortPlayPauseBtn.alpha = 0f
            binding.shortPlayPauseBtn.scaleX = 0.5f
            binding.shortPlayPauseBtn.scaleY = 0.5f
            binding.shortPlayPauseBtn.visibility = android.view.View.VISIBLE

            // Phase 1: scale up + fade in (spring-like)
            binding.shortPlayPauseBtn.animate()
                .alpha(0.9f)
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(100)
                .withEndAction {
                    // Phase 2: settle to normal then fade out
                    binding.shortPlayPauseBtn.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(80)
                        .withEndAction {
                            // Phase 3: fade out after a brief hold
                            binding.shortPlayPauseBtn.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction {
                                    // Keep icon visible for next tap
                                    binding.shortPlayPauseBtn.alpha = 0.85f
                                    binding.shortPlayPauseBtn.scaleX = 1f
                                    binding.shortPlayPauseBtn.scaleY = 1f
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }

        private fun loadAndPlay(videoUrl: String, position: Int) {
            scope.launch {
                binding.shortBuffering.visibility = View.VISIBLE
                try {
                    val videoId = videoUrl.toID()

                    // Check preload cache first
                    val cachedStreamUrl = streamCache[videoId]

                    if (cachedStreamUrl != null) {
                        // Use preloaded URL for instant playback
                        playerManager.setupPlayer(
                            position = position,
                            context = binding.root.context,
                            streamUrl = cachedStreamUrl,
                            playerView = binding.shortPlayerView,
                            onBuffering = { isBuffering ->
                                // Smooth fade in/out for buffering indicator
                                if (isBuffering) {
                                    binding.shortBuffering.alpha = 0f
                                    binding.shortBuffering.visibility = View.VISIBLE
                                    binding.shortBuffering.animate()
                                        .alpha(1f)
                                        .setDuration(200)
                                        .start()
                                } else {
                                    binding.shortBuffering.animate()
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction {
                                            binding.shortBuffering.visibility = View.GONE
                                        }
                                        .start()
                                }
                            },
                            onError = {
                                binding.shortBuffering.animate()
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction {
                                        binding.shortBuffering.visibility = View.GONE
                                    }
                                    .start()
                                // Auto-skip to next on error
                                onSkipToNext()
                            },
                            onEnded = { onSkipToNext() }
                        )
                        isPlaying = true
                        binding.shortPlayPauseBtn.setImageResource(com.atube.app.R.drawable.ic_pause)
                        // Apply current mute state
                        playerManager.setMuted(isMuted)
                        return@launch
                    }

                    val streams = withContext(Dispatchers.IO) {
                        MediaServiceRepository.instance.getStreams(videoId)
                    }

                    // Read user's Shorts quality preference
                    val qualityPref = PreferenceHelper.getString(
                        PreferenceKeys.SHORTS_VIDEO_QUALITY, ""
                    ).replace("p", "").toIntOrNull()

                    // Prefer HLS stream (has both audio + video in a single URL)
                    val hlsUrl = streams.hls
                    if (hlsUrl != null) {
                        streamCache[videoId] = hlsUrl
                        playerManager.setupPlayer(
                            position = position,
                            context = binding.root.context,
                            streamUrl = hlsUrl,
                            playerView = binding.shortPlayerView,
                            onBuffering = { isBuffering ->
                                if (isBuffering) {
                                    binding.shortBuffering.alpha = 0f
                                    binding.shortBuffering.visibility = View.VISIBLE
                                    binding.shortBuffering.animate()
                                        .alpha(1f)
                                        .setDuration(200)
                                        .start()
                                } else {
                                    binding.shortBuffering.animate()
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction {
                                            binding.shortBuffering.visibility = View.GONE
                                        }
                                        .start()
                                }
                            },
                            onError = {
                                binding.shortBuffering.animate()
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction {
                                        binding.shortBuffering.visibility = View.GONE
                                    }
                                    .start()
                                onSkipToNext()
                            },
                            onEnded = { onSkipToNext() }
                        )
                        isPlaying = true
                        binding.shortPlayPauseBtn.setImageResource(com.atube.app.R.drawable.ic_pause)
                        playerManager.setMuted(isMuted)
                        return@launch
                    }

                    // Find a combined video stream (audio + video in one stream)
                    val combinedStream = streams.videoStreams
                        .filter { it.url != null && it.videoOnly != true }
                        .let { candidates ->
                            if (qualityPref != null) {
                                candidates.filter { (it.height ?: 0) <= qualityPref }
                                    .maxByOrNull { it.height ?: 0 }
                            } else {
                                candidates.maxByOrNull { it.height ?: 0 }
                            }
                        }

                    if (combinedStream != null) {
                        streamCache[videoId] = combinedStream.url!!
                        playerManager.setupPlayer(
                            position = position,
                            context = binding.root.context,
                            streamUrl = combinedStream.url!!,
                            playerView = binding.shortPlayerView,
                            onBuffering = { isBuffering ->
                                if (isBuffering) {
                                    binding.shortBuffering.alpha = 0f
                                    binding.shortBuffering.visibility = View.VISIBLE
                                    binding.shortBuffering.animate()
                                        .alpha(1f)
                                        .setDuration(200)
                                        .start()
                                } else {
                                    binding.shortBuffering.animate()
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction {
                                            binding.shortBuffering.visibility = View.GONE
                                        }
                                        .start()
                                }
                            },
                            onError = {
                                binding.shortBuffering.animate()
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction {
                                        binding.shortBuffering.visibility = View.GONE
                                    }
                                    .start()
                                onSkipToNext()
                            },
                            onEnded = { onSkipToNext() }
                        )
                        isPlaying = true
                        binding.shortPlayPauseBtn.setImageResource(com.atube.app.R.drawable.ic_pause)
                        playerManager.setMuted(isMuted)
                        return@launch
                    }

                    // Last resort: merge best video-only stream with best audio stream
                    val bestVideo = streams.videoStreams
                        .filter { it.url != null }
                        .let { candidates ->
                            if (qualityPref != null) {
                                candidates.filter { (it.height ?: 0) <= qualityPref }
                                    .maxByOrNull { it.height ?: 0 }
                            } else {
                                candidates.maxByOrNull { it.height ?: 0 }
                            }
                        }
                    val bestAudio = streams.audioStreams
                        .filter { it.url != null }
                        .maxByOrNull { it.bitrate ?: 0 }

                    if (bestVideo?.url != null && bestAudio?.url != null) {
                        playerManager.setupPlayerWithMerge(
                            position = position,
                            context = binding.root.context,
                            videoUrl = bestVideo.url!!,
                            audioUrl = bestAudio.url!!,
                            playerView = binding.shortPlayerView,
                            onBuffering = { isBuffering ->
                                if (isBuffering) {
                                    binding.shortBuffering.alpha = 0f
                                    binding.shortBuffering.visibility = View.VISIBLE
                                    binding.shortBuffering.animate()
                                        .alpha(1f)
                                        .setDuration(200)
                                        .start()
                                } else {
                                    binding.shortBuffering.animate()
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction {
                                            binding.shortBuffering.visibility = View.GONE
                                        }
                                        .start()
                                }
                            },
                            onError = {
                                binding.shortBuffering.animate()
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction {
                                        binding.shortBuffering.visibility = View.GONE
                                    }
                                    .start()
                                onSkipToNext()
                            },
                            onEnded = { onSkipToNext() }
                        )
                        isPlaying = true
                        binding.shortPlayPauseBtn.setImageResource(com.atube.app.R.drawable.ic_pause)
                        playerManager.setMuted(isMuted)
                    } else {
                        // No usable stream found
                        binding.shortBuffering.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction {
                                binding.shortBuffering.visibility = View.GONE
                            }
                            .start()
                    }

                } catch (e: Exception) {
                    binding.shortBuffering.animate()
                        .alpha(0f)
                        .setDuration(150)
                        .withEndAction {
                            binding.shortBuffering.visibility = View.GONE
                        }
                        .start()
                    // Auto-skip to next on load failure
                    onSkipToNext()
                }
            }
        }

        fun pause() {
            playerManager.pause(bindingAdapterPosition)
            isPlaying = false
        }

    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StreamItem>() {
            override fun areItemsTheSame(old: StreamItem, new: StreamItem): Boolean =
                old.url == new.url
            override fun areContentsTheSame(old: StreamItem, new: StreamItem): Boolean =
                old == new
        }
    }
}

/**
 * Manages a single ExoPlayer instance for the Shorts feed.
 *
 * Only one player is active at a time. When a new page is selected,
 * the previous player is released and a new one is created.
 *
 * This prevents memory leaks and ensures clean lifecycle management.
 */
class ShortsPlayerManager {
    private var currentPlayer: ExoPlayer? = null
    private var currentPlayerView: androidx.media3.ui.PlayerView? = null
    private var currentPosition = -1
    private var previousVolume = 1f

    private fun releaseCurrentPlayer() {
        currentPlayerView?.player = null
        currentPlayer?.release()
        currentPlayer = null
        currentPlayerView = null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setupPlayer(
        position: Int,
        context: android.content.Context,
        streamUrl: String,
        playerView: androidx.media3.ui.PlayerView,
        onBuffering: (Boolean) -> Unit,
        onError: () -> Unit,
        onEnded: () -> Unit = {}
    ) {
        // Detach and release the previous page before creating the active player.
        releaseCurrentPlayer()
        currentPosition = position

        // Create new player with custom User-Agent to avoid 403 Forbidden errors
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        currentPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .build()
        playerView.player = currentPlayer
        currentPlayerView = playerView

        val mediaItem = MediaItem.fromUri(streamUrl)
        currentPlayer?.setMediaItem(mediaItem)
        currentPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        currentPlayer?.prepare()
        currentPlayer?.playWhenReady = true

        currentPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                onBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                onError()
            }
        })
    }

    /**
     * Set up player with separate video and audio streams merged via MergingMediaSource.
     * This is needed when Piped API returns video-only and audio-only streams separately.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setupPlayerWithMerge(
        position: Int,
        context: android.content.Context,
        videoUrl: String,
        audioUrl: String,
        playerView: androidx.media3.ui.PlayerView,
        onBuffering: (Boolean) -> Unit,
        onError: () -> Unit,
        onEnded: () -> Unit = {}
    ) {
        releaseCurrentPlayer()
        currentPosition = position
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        currentPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .build()
        playerView.player = currentPlayer
        currentPlayerView = playerView

        val mediaSourceFactory = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)

        val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(videoUrl))
        val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
        val mergedSource = androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)

        currentPlayer?.setMediaSource(mergedSource)
        currentPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        currentPlayer?.prepare()
        currentPlayer?.playWhenReady = true

        currentPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                onBuffering(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                onError()
            }
        })
    }

    fun playAt(position: Int) {
        // Only one player active at a time — managed per position
        currentPosition = position
        currentPlayer?.playWhenReady = true
    }

    fun pause(position: Int) {
        if (position == currentPosition) {
            currentPlayer?.playWhenReady = false
        }
    }

    fun isPlaying(): Boolean = currentPlayer?.isPlaying == true

    fun resume(position: Int) {
        if (position == currentPosition) {
            currentPlayer?.playWhenReady = true
        }
    }

    fun setMuted(muted: Boolean) {
        if (muted) {
            previousVolume = currentPlayer?.volume ?: 1f
            currentPlayer?.volume = 0f
        } else {
            currentPlayer?.volume = previousVolume
        }
    }

    fun release() {
        releaseCurrentPlayer()
        currentPosition = -1
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}
