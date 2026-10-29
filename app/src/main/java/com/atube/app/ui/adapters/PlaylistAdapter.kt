package com.atube.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import com.atube.app.api.obj.StreamItem
import com.atube.app.constants.IntentData
import com.atube.app.databinding.VideoRowBinding
import com.atube.app.db.DatabaseHolder
import com.atube.app.extensions.toID
import com.atube.app.helpers.ImageHelper
import com.atube.app.helpers.NavigationHelper
import com.atube.app.ui.adapters.callbacks.DiffUtilItemCallback
import com.atube.app.ui.base.BaseActivity
import com.atube.app.ui.extensions.setFormattedDuration
import com.atube.app.ui.extensions.setWatchProgressLength
import com.atube.app.ui.sheets.VideoOptionsBottomSheet
import com.atube.app.ui.sheets.VideoOptionsBottomSheet.Companion.VIDEO_OPTIONS_SHEET_REQUEST_KEY
import com.atube.app.ui.viewholders.PlaylistViewHolder
import com.atube.app.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistItem(
    val item: StreamItem,
    /**
     * The original index of the playlist item before sorting the feed.
     */
    val originalPlaylistIndex: Int,
)

class PlaylistAdapter(
    private val playlistId: String,
    private val onVideoClick: (StreamItem) -> Unit
) : ListAdapter<PlaylistItem, PlaylistViewHolder>(DiffUtilItemCallback(
    // the index is not relevant for whether the playlist videos are the same
    // hence only compare the videos themselves
    areItemsTheSame = { a, b -> a.item == b.item },
    areContentsTheSame = { a, b -> a.item == b.item },
)) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = VideoRowBinding.inflate(layoutInflater, parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val (streamItem, _) = getItem(position)!!
        val videoId = streamItem.url!!.toID()

        holder.binding.apply {
            videoTitle.text = streamItem.title
            videoInfo.text = TextUtils.formatViewsString(root.context, streamItem.views ?: -1, streamItem.uploaded)
            videoInfo.maxLines = 2

            // piped does not load channel avatars for playlist views
            channelImageContainer.isGone = true
            channelName.text = streamItem.uploaderName

            ImageHelper.loadImageHighQuality(streamItem.thumbnail, thumbnail)
            thumbnailDuration.setFormattedDuration(streamItem.duration ?: -1, streamItem.isShort, streamItem.uploaded)

            root.setOnClickListener {
                onVideoClick(streamItem)
            }

            val activity = (root.context as BaseActivity)
            val fragmentManager = activity.supportFragmentManager
            root.setOnLongClickListener {
                fragmentManager.setFragmentResultListener(
                    VIDEO_OPTIONS_SHEET_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    notifyItemChanged(position)
                }
                VideoOptionsBottomSheet().apply {
                    arguments = bundleOf(
                        IntentData.streamItem to streamItem,
                        IntentData.playlistId to playlistId
                    )
                }
                    .show(fragmentManager, VideoOptionsBottomSheet::class.java.name)
                true
            }

            if (!streamItem.uploaderUrl.isNullOrBlank()) {
                channelContainer.setOnClickListener {
                    NavigationHelper.navigateChannel(root.context, streamItem.uploaderUrl)
                }
            }

            streamItem.duration?.let { watchProgress.setWatchProgressLength(videoId, it) }

            CoroutineScope(Dispatchers.IO).launch {
                val isDownloaded =
                    DatabaseHolder.Database.downloadDao().exists(videoId)

                withContext(Dispatchers.Main) {
                    downloadBadge.isVisible = isDownloaded
                }
            }
        }
    }
}
