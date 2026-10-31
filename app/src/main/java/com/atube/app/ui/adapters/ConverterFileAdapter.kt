package com.atube.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.databinding.ItemConverterFileBinding
import java.nio.file.Path

/** One file offered on the converter screen. */
data class ConverterFile(
    val videoId: String,
    val title: String,
    val meta: String,
    val path: Path
)

class ConverterFileAdapter(
    private val onSelect: (ConverterFile) -> Unit
) : ListAdapter<ConverterFile, ConverterFileAdapter.Holder>(Diff) {

    private var selectedId: String? = null

    fun setSelected(videoId: String?) {
        val previous = selectedId
        selectedId = videoId
        currentList.forEachIndexed { index, file ->
            if (file.videoId == previous || file.videoId == videoId) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemConverterFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemConverterFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ConverterFile) {
            binding.fileTitle.text = item.title
            binding.fileMeta.text = item.meta
            binding.fileSelected.isChecked = item.videoId == selectedId
            binding.root.setOnClickListener { onSelect(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ConverterFile>() {
        override fun areItemsTheSame(oldItem: ConverterFile, newItem: ConverterFile) =
            oldItem.videoId == newItem.videoId && oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: ConverterFile, newItem: ConverterFile) =
            oldItem == newItem
    }
}
