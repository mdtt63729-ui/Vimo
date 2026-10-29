package com.atube.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.databinding.ItemCategoryChipBinding
import com.google.android.material.chip.Chip

/**
 * Adapter for the horizontal category chip bar on the Home screen.
 *
 * Chips represent categories like "All", "Explore", "Gaming", "Music", etc.
 * Selecting a chip triggers re-ranking (not fragment recreation).
 *
 * Performance:
 * - Stable IDs for correct RecyclerView recycling
 * - No layout jump on selection (chip width stays stable)
 * - notifyItemChanged with payload to prevent full rebind
 */
class CategoryChipsAdapter(
    private val categories: List<String>,
    private val onCategorySelected: (String) -> Unit
) : RecyclerView.Adapter<CategoryChipsAdapter.ChipViewHolder>() {

    private var selectedPosition = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return categories[position].hashCode().toLong()
    }

    fun setSelectedCategory(category: String) {
        val newIndex = categories.indexOf(category)
        if (newIndex >= 0 && newIndex != selectedPosition) {
            val oldPos = selectedPosition
            selectedPosition = newIndex
            notifyItemChanged(oldPos, PAYLOAD_SELECTION)
            notifyItemChanged(selectedPosition, PAYLOAD_SELECTION)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val binding = ItemCategoryChipBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        val category = categories[position]
        holder.bind(category, position == selectedPosition)
    }

    override fun onBindViewHolder(
        holder: ChipViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            // Only update selection state, don't rebind text
            holder.updateSelection(position == selectedPosition)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = categories.size

    inner class ChipViewHolder(
        private val binding: ItemCategoryChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val oldPos = selectedPosition
                selectedPosition = bindingAdapterPosition
                if (oldPos != selectedPosition) {
                    notifyItemChanged(oldPos, PAYLOAD_SELECTION)
                    notifyItemChanged(selectedPosition, PAYLOAD_SELECTION)
                    onCategorySelected(categories[selectedPosition])
                }
            }
        }

        fun bind(category: String, isSelected: Boolean) {
            (binding.root as Chip).apply {
                text = category
                isChecked = isSelected
            }
        }

        fun updateSelection(isSelected: Boolean) {
            (binding.root as Chip).isChecked = isSelected
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }
}
