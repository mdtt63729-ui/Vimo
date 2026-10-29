package com.atube.app.ui.views

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.atube.app.R

/**
 * Animated shimmer for skeleton loading placeholders.
 *
 * Ported from the Off app's Compose `SkeletonBox` so both apps share one look:
 *
 *  - the base and highlight colours come from the Material 3 theme
 *    (`surfaceContainerHigh` / `surfaceContainerHighest`), so the placeholder
 *    follows the app's light, dark and dynamic themes instead of a hardcoded grey
 *  - one sweep crosses the view in 1300 ms, linearly, and restarts
 *  - the sweep is 55% of the view's width, so it looks the same on a thumbnail
 *    and on a full-width card
 *
 * Use [ShimmerHelper.applyShimmer] to attach it to a View.
 */
object ShimmerHelper {

    /** Default corner radius, matching the rounded cards in the feed. */
    const val DEFAULT_RADIUS_DP = 12f

    /**
     * Apply the animated shimmer to a View's background, replacing whatever
     * background it had. The view keeps its rounded shape.
     */
    fun applyShimmer(view: View, cornerRadiusDp: Float = DEFAULT_RADIUS_DP) {
        val radiusPx = cornerRadiusDp * view.resources.displayMetrics.density
        val drawable = ShimmerDrawable(
            // Taken from resources rather than the theme's surface-container
            // attributes: those resolve to something dark in some of A Tube's
            // light themes, which made the placeholders read as black blocks.
            // values/ and values-night/ carry the light and dark greys.
            baseColor = ContextCompat.getColor(view.context, R.color.skeleton_bg),
            highlightColor = ContextCompat.getColor(view.context, R.color.skeleton_highlight),
            cornerRadiusPx = radiusPx
        )
        view.background = drawable
        drawable.start()
    }

    /** Apply the shimmer to a View and every descendant that is a plain ViewGroup child. */
    fun applyShimmerRecursively(root: View) {
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) applyShimmerRecursively(root.getChildAt(i))
        } else {
            applyShimmer(root)
        }
    }

    /** Stop the animation and clear the background. */
    fun stopShimmer(view: View) {
        (view.background as? ShimmerDrawable)?.stop()
        view.background = null
    }

}

/**
 * A rounded rectangle filled with a looping horizontal highlight.
 *
 * Everything is drawn in one pass: the base colour, then a linear gradient that
 * slides across the view, so the cost is one extra draw per frame and nothing is
 * allocated while animating.
 */
class ShimmerDrawable(
    private val baseColor: Int,
    private val highlightColor: Int,
    private val cornerRadiusPx: Float = 0f
) : Drawable(), Animatable {

    constructor(view: View) : this(
        baseColor = ContextCompat.getColor(view.context, R.color.skeleton_bg),
        highlightColor = ContextCompat.getColor(view.context, R.color.skeleton_highlight),
        cornerRadiusPx = ShimmerHelper.DEFAULT_RADIUS_DP * view.resources.displayMetrics.density
    )

    /** Kept so older call sites that built the drawable without a context still work. */
    constructor() : this(
        baseColor = Color.parseColor("#E6E6E6"),
        highlightColor = Color.parseColor("#F2F2F2"),
        cornerRadiusPx = 0f
    )

    private var animator: ValueAnimator? = null
    private var translate = 0f
    private var running = false

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clipPath = Path()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        rect.set(bounds)
        val rounded = cornerRadiusPx > 0f
        if (rounded) {
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
        }

        canvas.drawRect(rect, basePaint)

        // The sweep is 55% of the view's width, exactly like the Compose version.
        val width = rect.width()
        val sweep = width * SWEEP_FRACTION
        val startX = -sweep + translate * (width + 2f * sweep)
        shimmerPaint.shader = LinearGradient(
            startX,
            0f,
            startX + sweep,
            0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, shimmerPaint)

        if (rounded) canvas.restore()
    }

    override fun start() {
        if (running) return
        running = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                translate = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun stop() {
        running = false
        animator?.cancel()
        animator = null
    }

    override fun isRunning(): Boolean = running

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        shimmerPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        basePaint.colorFilter = colorFilter
        shimmerPaint.colorFilter = colorFilter
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        const val SWEEP_DURATION_MS = 1300L
        const val SWEEP_FRACTION = 0.55f
    }
}
