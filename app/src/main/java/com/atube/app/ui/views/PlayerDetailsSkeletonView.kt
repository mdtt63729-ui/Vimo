package com.atube.app.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.atube.app.R

/**
 * The skeleton shown in the player's details column while the stream info is
 * still being fetched.
 *
 * It reuses the home feed's [ShimmerHelper], so the placeholder blocks on this
 * screen and on the home screen are the same greys sweeping at the same speed.
 * Every block is a plain `View` in `view_player_details_skeleton.xml`; the
 * rounded shape and the animation are attached here, because the corner radius
 * depends on how the block was measured (a square block becomes a circle).
 */
class PlayerDetailsSkeletonView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attributeSet, defStyleAttr) {

    private var shimmerRunning = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_player_details_skeleton, this, true)
    }

    /**
     * Attach the shimmer to every placeholder block. Safe to call again after
     * [stopShimmer]: it re-applies the drawable, which the stop clears.
     */
    fun startShimmer() {
        if (shimmerRunning) return
        shimmerRunning = true
        post { if (shimmerRunning) attachShimmer(this) }
    }

    /** Stop the animation and clear the placeholder backgrounds. */
    fun stopShimmer() {
        shimmerRunning = false
        removeShimmer(this)
    }

    private fun attachShimmer(view: View) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) attachShimmer(view.getChildAt(index))
            return
        }

        val width = view.width
        val height = view.height
        // A square block is an avatar, so it gets the full radius and reads as a
        // circle; everything else is a rounded card block.
        val radiusDp = if (width > 0 && width == height) {
            width / resources.displayMetrics.density / 2f
        } else {
            ShimmerHelper.DEFAULT_RADIUS_DP
        }
        ShimmerHelper.applyShimmer(view, cornerRadiusDp = radiusDp)
    }

    private fun removeShimmer(view: View) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) removeShimmer(view.getChildAt(index))
            return
        }
        ShimmerHelper.stopShimmer(view)
    }

    override fun onDetachedFromWindow() {
        // never leave the sweep animating once the screen is gone
        stopShimmer()
        super.onDetachedFromWindow()
    }
}
