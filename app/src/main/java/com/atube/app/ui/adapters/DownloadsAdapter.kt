package com.atube.app.ui.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import com.atube.app.R
import com.atube.app.constants.IntentData
import com.atube.app.databinding.VideoRowBinding
import com.atube.app.db.DatabaseHelper
import com.atube.app.db.obj.DownloadWithItems
import com.atube.app.extensions.formatAsFileSize
import com.atube.app.helpers.DownloadHelper
import com.atube.app.helpers.ImageHelper
import com.atube.app.helpers.NavigationHelper
import com.atube.app.parcelable.PlayerData
import com.atube.app.ui.adapters.callbacks.DiffUtilItemCallback
import com.atube.app.ui.base.BaseActivity
import com.atube.app.ui.extensions.setWatchProgressLength
import com.atube.app.ui.fragments.DownloadSortingOrder
import com.atube.app.ui.fragments.DownloadTab
import com.atube.app.ui.sheets.DownloadOptionsBottomSheet
import com.atube.app.ui.sheets.DownloadOptionsBottomSheet.Companion.DELETE_DOWNLOAD_REQUEST_KEY
import com.atube.app.ui.viewholders.DownloadsViewHolder
import com.atube.app.util.TextUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.io.path.exists
import kotlin.io.path.fileSize

class DownloadsAdapter(
    private val context: Context,
    private val downloadTab: DownloadTab,
    private val playlistId: String?,
    private val currentSortOrder: () -> DownloadSortingOrder,
    private val toggleDownload: (DownloadWithItems) -> Boolean
) : ListAdapter<DownloadWithItems, DownloadsViewHolder>(DiffUtilItemCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadsViewHolder {
        val binding = VideoRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DownloadsViewHolder(binding)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: DownloadsViewHolder, position: Int) {
        val downloadWithItems = getItem(holder.bindingAdapterPosition)
        val (download, items, _) = downloadWithItems

        holder.binding.apply {
            fileSize.isVisible = true

            channelImageContainer.isGone = true
            videoTitle.text = download.title
            channelName.text = download.uploader
            videoInfo.text = download.uploadDate?.let { TextUtils.localizeDate(it) }
            watchProgress.setWatchProgressLength(download.videoId, download.duration ?: 0)

            val downloadSize = items.sumOf { it.downloadSize }
            val currentSize = items.filter { it.path.exists() }.sumOf { it.path.fileSize() }

            if (downloadSize == -1L) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.max = downloadSize.toInt()
                progressBar.progress = currentSize.toInt()
            }

            val totalSizeInfo = if (downloadSize > 0) {
                downloadSize.formatAsFileSize()
            } else {
                context.getString(R.string.unknown)
            }
            if (downloadSize > currentSize) {
                downloadOverlay.isVisible = true
                resumePauseBtn.setImageResource(R.drawable.ic_download)
                fileSize.text = "${currentSize.formatAsFileSize()} / $totalSizeInfo"
            } else {
                downloadOverlay.isGone = true
                fileSize.text = totalSizeInfo
                thumbnailDurationCard.isVisible = true
                download.duration?.let {
                    thumbnailDuration.text = DateUtils.formatElapsedTime(it)
                }
            }

            download.thumbnailPath?.let { path ->
                ImageHelper.loadImage(path.toString(), thumbnail)
            }

            progressBar.setOnClickListener {
                val isDownloading = toggleDownload(getItem(holder.bindingAdapterPosition))

                resumePauseBtn.setImageResource(
                    if (isDownloading) {
                        R.drawable.ic_pause
                    } else {
                        R.drawable.ic_download
                    }
                )
            }

            root.setOnClickListener {
                val playerData = PlayerData(
                    videoId = download.videoId,
                    playlistId = playlistId,
                    downloadTab = downloadTab,
                    downloadSortingOrder = currentSortOrder(),
                    isOffline = true
                )

                when (downloadTab) {
                    DownloadTab.VIDEO, DownloadTab.PLAYLIST-> {
                        NavigationHelper.navigateVideo(root.context, playerData)
                    }
                    DownloadTab.AUDIO -> {
                        NavigationHelper.navigateVideo(
                            root.context,
                            playerData,
                            audioOnlyPlayerRequested = true
                        )
                    }
                }
            }

            root.setOnLongClickListener {
                val activity = root.context as BaseActivity
                val fragmentManager = activity.supportFragmentManager
                fragmentManager.setFragmentResultListener(
                    DELETE_DOWNLOAD_REQUEST_KEY,
                    activity
                ) { _, _ ->
                    // the position might have changed in the meanwhile if an other item was deleted
                    // apparently [onBindViewHolder] is only retriggered if the item changes, but
                    // not if the position changes (which would lead to IndexOutOfBounds here)
                    val realPosition = currentList.indexOf(downloadWithItems)
                    showDeleteDialog(root.context, realPosition)
                }
                DownloadOptionsBottomSheet()
                    .apply {
                        arguments = bundleOf(
                            IntentData.streamItem to download.toStreamItem(),
                            IntentData.playlistId to playlistId,
                            IntentData.downloadTab to downloadTab
                        )
                    }
                    .show(fragmentManager)
                true
            }
        }
    }

    fun showDeleteDialog(context: Context, position: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete)
            .setMessage(R.string.irreversible)
            .setPositiveButton(R.string.okay) { _, _ ->
                deleteDownload(position)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteDownload(position: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            DownloadHelper.deleteDownloadIncludingFiles(getItem(position))

            withContext(Dispatchers.Main) {
                submitList(currentList.toMutableList().also {
                    it.removeAt(position)
                })
            }
        }
    }

    fun deleteAllDownloads(onlyDeleteWatched: Boolean) {
        val (toDelete, toKeep) = currentList.partition {
            !onlyDeleteWatched || runBlocking(Dispatchers.IO) {
                DatabaseHelper.isVideoWatched(it.download.videoId, it.download.duration ?: 0)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            for (item in toDelete) {
                DownloadHelper.deleteDownloadIncludingFiles(item)
            }

            withContext(Dispatchers.Main) {
                submitList(toKeep)
            }
        }
    }

    fun restoreItem(position: Int) {
        // moves the item back to its initial horizontal position
        notifyItemRemoved(position)
        notifyItemInserted(position)
    }
}
