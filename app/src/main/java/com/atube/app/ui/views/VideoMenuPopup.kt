package com.atube.app.ui.views

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import com.atube.app.databinding.ViewVideoMenuBinding

/**
 * The three-dot menu on a video card: a small rounded card that floats above
 * everything, on a light or a dark screen alike.
 *
 * A PopupWindow with an elevation is used rather than PopupMenu because PopupMenu
 * draws the platform list style: square corners, no icons, and it is invisible
 * against some backgrounds.
 */
class VideoMenuPopup(
    private val anchor: View,
    private val onAction: (Action) -> Unit
) {
    enum class Action { PLAY_NEXT, PLAY_LAST, DOWNLOAD, SAVE, SHARE, NOT_INTERESTED, DONT_RECOMMEND, REPORT }

    fun show() {
        val context = anchor.context
        val binding = ViewVideoMenuBinding.inflate(LayoutInflater.from(context))
        val density = anchor.resources.displayMetrics.density

        val popup = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 18f * density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
        }

        fun wire(view: View, action: Action) {
            view.setOnClickListener {
                popup.dismiss()
                onAction(action)
            }
        }

        wire(binding.rowPlayNext, Action.PLAY_NEXT)
        wire(binding.rowPlayLast, Action.PLAY_LAST)
        wire(binding.rowDownload, Action.DOWNLOAD)
        wire(binding.rowSave, Action.SAVE)
        wire(binding.rowShare, Action.SHARE)
        wire(binding.rowNotInterested, Action.NOT_INTERESTED)
        wire(binding.rowDontRecommend, Action.DONT_RECOMMEND)
        wire(binding.rowReport, Action.REPORT)

        // measured so it can be nudged back on screen when the anchor sits near the edge
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = binding.root.measuredWidth
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val screenWidth = anchor.resources.displayMetrics.widthPixels
        val offsetX = (screenWidth - location[0] - width).coerceAtMost(0) - (8 * density).toInt()
        val offsetY = (4 * density).toInt()

        popup.showAsDropDown(anchor, offsetX, offsetY)
    }
}
