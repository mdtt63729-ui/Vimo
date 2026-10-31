package com.atube.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.databinding.ItemYtdlpFormatBinding
import com.atube.app.databinding.ItemYtdlpVideoBinding
import com.atube.app.helpers.ImageHelper
import java.util.Locale

/** One row on the fetch screen: either a video, or one of its formats. */
sealed class YtDlpResult {
    data class Video(
        val id: String,
        val title: String,
        val uploader: String,
        val duration: Long,
        val thumbnail: String,
        val views: Long,
        val url: String,
        /** width / height of the real video, so the card keeps its shape. */
        val aspect: Float = 16f / 9f
    ) : YtDlpResult()

    data class Format(
        val label: String,
        val detail: String,
        /** Direct stream URL, used for copying. */
        val directUrl: String,
        /** yt-dlp format selector, used for the actual download. */
        val formatId: String,
        /** The page the format came from. */
        val pageUrl: String,
        val isAudio: Boolean,
        /** true when this row downloads the audio track and converts it to MP3. */
        val convertToMp3: Boolean = false
    ) : YtDlpResult()
}

class YtDlpFetchAdapter(
    private val onVideoClick: (YtDlpResult.Video) -> Unit,
    private val onFormatDownload: (YtDlpResult.Format) -> Unit
) : ListAdapter<YtDlpResult, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is YtDlpResult.Video) TYPE_VIDEO else TYPE_FORMAT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoHolder(ItemYtdlpVideoBinding.inflate(inflater, parent, false))
        } else {
            FormatHolder(ItemYtdlpFormatBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is YtDlpResult.Video -> (holder as VideoHolder).bind(item)
            is YtDlpResult.Format -> (holder as FormatHolder).bind(item)
        }
    }

    inner class VideoHolder(private val binding: ItemYtdlpVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: YtDlpResult.Video) {
            binding.resultTitle.text = item.title
            binding.resultSubtitle.text = listOf(
                item.uploader.ifBlank { "Unknown channel" },
                formatDuration(item.duration)
            ).filter { it.isNotBlank() }.joinToString(" · ")
            binding.resultMeta.text = if (item.views > 0) {
                "${formatCount(item.views)} views"
            } else {
                "Tap to load every available format"
            }

            // Keep the thumbnail in the shape of the real video instead of forcing
            // every card into 16:9.
            val density = binding.root.resources.displayMetrics.density
            val width = (132 * density).toInt()
            val aspect = item.aspect.takeIf { it > 0.2f && it < 5f } ?: (16f / 9f)
            val height = (width / aspect).toInt().coerceIn((56 * density).toInt(), (240 * density).toInt())
            binding.resultThumbnail.layoutParams = binding.resultThumbnail.layoutParams.apply {
                this.width = width
                this.height = height
            }

            ImageHelper.loadImageHighQuality(item.thumbnail, binding.resultThumbnail)
            binding.root.setOnClickListener { onVideoClick(item) }
        }
    }

    inner class FormatHolder(private val binding: ItemYtdlpFormatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: YtDlpResult.Format) {
            binding.formatLabel.text = item.label
            binding.formatDetail.text = item.detail
            binding.root.setOnClickListener { onFormatDownload(item) }
            binding.formatAction.setOnClickListener { onFormatDownload(item) }
        }
    }

    companion object {
        private const val TYPE_VIDEO = 0
        private const val TYPE_FORMAT = 1

        fun formatDuration(seconds: Long): String {
            if (seconds <= 0L) return ""
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        fun formatCount(count: Long): String = when {
            count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }

        fun formatSize(bytes: Long): String = when {
            bytes <= 0L -> ""
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }

    private object Diff : DiffUtil.ItemCallback<YtDlpResult>() {
        override fun areItemsTheSame(oldItem: YtDlpResult, newItem: YtDlpResult): Boolean {
            return when {
                oldItem is YtDlpResult.Video && newItem is YtDlpResult.Video -> oldItem.id == newItem.id
                oldItem is YtDlpResult.Format && newItem is YtDlpResult.Format ->
                    oldItem.formatId == newItem.formatId && oldItem.pageUrl == newItem.pageUrl
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: YtDlpResult, newItem: YtDlpResult): Boolean =
            oldItem == newItem
    }
}
