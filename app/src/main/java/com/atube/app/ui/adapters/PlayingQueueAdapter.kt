package com.atube.app.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.databinding.QueueRowBinding
import com.atube.app.extensions.toID
import com.atube.app.helpers.ImageHelper
import com.atube.app.helpers.ThemeHelper
import com.atube.app.ui.viewholders.PlayingQueueViewHolder
import com.atube.app.util.PlayingQueue

class PlayingQueueAdapter(
    private val onQueueItemSelected: (String) -> Unit
) : RecyclerView.Adapter<PlayingQueueViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlayingQueueViewHolder {
        val binding = QueueRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlayingQueueViewHolder(binding)
    }

    override fun getItemCount() = PlayingQueue.size()

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: PlayingQueueViewHolder, position: Int) {
        val streamItem = PlayingQueue.getStreams()[position]
        holder.binding.apply {
            ImageHelper.loadImageHighQuality(streamItem.thumbnail, thumbnail)
            title.text = streamItem.title
            videoInfo.text = streamItem.uploaderName + "  •  " +
                DateUtils.formatElapsedTime(streamItem.duration ?: 0)

            val currentIndex = PlayingQueue.currentIndex()
            root.setBackgroundColor(
                if (currentIndex == position) {
                    ThemeHelper.getThemeColor(root.context, android.R.attr.colorControlHighlight)
                } else {
                    Color.TRANSPARENT
                }
            )

            root.setOnClickListener {
                val newVideoId = streamItem.url?.toID() ?: return@setOnClickListener

                val oldPosition = PlayingQueue.currentIndex()
                // get the new position from the queue to work properly after reordering the queue
                val newPosition = PlayingQueue.getStreams().indexOfFirst {
                    it.url?.toID() == newVideoId
                }.takeIf { it >= 0 } ?: return@setOnClickListener
                PlayingQueue.updateCurrent(streamItem)

                // select the new item in the queue and update the selected item in the UI
                onQueueItemSelected(newVideoId)
                notifyItemChanged(oldPosition)
                notifyItemChanged(newPosition)
            }
        }
    }
}
