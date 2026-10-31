package com.atube.app.ui.views

import com.atube.app.R

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Floating pill navigation bar.
 *
 * One spring-driven progress value drives everything - slot widths, the moving
 * pill, icon scale and the label reveal - so nothing can drift out of step.
 *
 * Widths are computed from the labels as they are actually drawn (measured with
 * each TextView's own paint), and every label is given that exact width. The old
 * version measured with a throwaway Paint and then let LinearLayout size the
 * label with WRAP_CONTENT inside a proportional slot, which is what clipped
 * "Downloader" down to "Do".
 */
class Material3FloatingNavBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    data class Destination(val label: String, val iconRes: Int)

    // Shorts is deliberately absent: three destinations for now.
    private val destinations = listOf(
        Destination("Home", R.drawable.ic_nav_home),
        Destination("Downloader", R.drawable.ic_nav_downloader),
        Destination("Library", R.drawable.ic_nav_library)
    )

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val surfaceColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorSurfaceContainerHigh,
        0xFFF0EDF6.toInt()
    )
    private val indicatorColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorPrimaryContainer,
        0xFFD0C5E8.toInt()
    )
    private val selectedContentColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnPrimaryContainer,
        0xFF342B49.toInt()
    )
    private val unselectedContentColor = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurfaceVariant,
        0xFF494551.toInt()
    )

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = indicatorColor }
    private val indicatorRect = RectF()

    private val itemViews = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()

    private var progress = 0f
    private var selected = 0
    private var target = 0
    private var spring: SpringAnimation? = null
    var onItemSelected: ((Int) -> Unit)? = null

    // ---- geometry -----------------------------------------------------------
    private val barHeight = dp(64f).toInt()
    private val innerHeight = dp(48f).toInt()
    /** Width reserved for the icon, drawn at 24dp and centred inside it. */
    private val iconBox = dp(48f)
    private val iconSize = dp(24f).toInt()
    private val labelGap = dp(6f)
    private val labelEndPadding = dp(16f)
    private val outerInset = dp(6f)
    private val pillInset = dp(4f)
    private val cornerRadius = dp(32f)

    private val labelTextSize = 13f

    init {
        setWillNotDraw(false)
        isClickable = false
        elevation = dp(10f)
        // The background also gives elevation its rounded outline, so the bar casts
        // a proper shadow instead of a rectangle.
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = this@Material3FloatingNavBarView.cornerRadius
        }

        destinations.forEachIndexed { index, destination ->
            val slot = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = true
                clipToPadding = true
                isClickable = true
                isFocusable = true
                contentDescription = destination.label
                setOnClickListener { select(index, notify = true) }
            }

            val icon = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, destination.iconRes))
                imageTintList = ColorStateList.valueOf(
                    if (index == selected) selectedContentColor else unselectedContentColor
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconBox.roundToInt(), iconSize)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val label = TextView(context).apply {
                text = destination.label
                textSize = labelTextSize
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(selectedContentColor)
                alpha = if (index == 0) 1f else 0f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            slot.addView(icon)
            slot.addView(label)
            addView(slot)
            itemViews += slot
            iconViews += icon
            labelViews += label
        }

        // The label widths are only known once the TextViews exist, so the exact
        // widths are assigned here rather than at construction time.
        labelViews.forEach { label ->
            label.layoutParams = LinearLayout.LayoutParams(
                labelWidthOf(label).roundToInt(), innerHeight
            ).apply { marginStart = labelGap.roundToInt() }
        }

        contentDescription = "Bottom navigation"
    }

    /** Width the label needs, measured with the paint the TextView draws with. */
    private fun labelWidthOf(label: TextView): Float =
        label.paint.measureText(label.text.toString()) + dp(1f)

    /** Selects a destination. The index is clamped to the available items. */
    @JvmOverloads
    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        select(index, notify = false, animate = animate)
    }

    fun getSelectedIndex(): Int = selected

    private fun select(index: Int, notify: Boolean, animate: Boolean = true) {
        val safeIndex = index.coerceIn(destinations.indices)

        // Already settled, or already gliding to this same slot: leave the spring
        // alone instead of restarting it from zero velocity mid-flight.
        val settled = safeIndex == selected && abs(progress - safeIndex) < 0.001f
        val alreadyHeading = spring?.isRunning == true && target == safeIndex
        if (settled || alreadyHeading) {
            if (notify) onItemSelected?.invoke(safeIndex)
            return
        }

        selected = safeIndex
        target = safeIndex
        itemViews.forEachIndexed { i, view ->
            view.isSelected = i == selected
            view.contentDescription =
                if (i == selected) "${destinations[i].label}, selected" else destinations[i].label
        }

        spring?.cancel()
        if (animate) {
            spring = SpringAnimation(this, PROGRESS_PROPERTY).apply {
                spring = SpringForce(safeIndex.toFloat()).apply {
                    // near-critical damping: one clean glide, no wobble at the end
                    dampingRatio = DAMPING
                    stiffness = STIFFNESS
                }
                setStartVelocity(0f)
                start()
            }
        } else {
            progress = safeIndex.toFloat()
            layoutSlots()
            updateAnimatedChildren()
            invalidate()
        }

        if (notify) onItemSelected?.invoke(safeIndex)
    }

    // ---- measurement --------------------------------------------------------

    private fun contentWidth(): Float = contentWidthOf(width)

    private fun contentWidthOf(viewWidth: Int): Float =
        (viewWidth - 2 * outerInset).coerceAtLeast(1f)

    /**
     * Slot widths for the current progress.
     *
     * Every slot always reserves [iconBox]. The slot being selected additionally
     * reserves the gap, the label's measured width and a trailing padding, so a
     * label can never be squeezed below the width it needs. Whatever is left over
     * is shared out evenly, which is what keeps the icons from bunching up.
     */
    private fun slotWidths(content: Float): FloatArray {
        val count = destinations.size
        val widths = FloatArray(count) { iconBox }
        destinations.indices.forEach { i ->
            val expand = (1f - abs(progress - i)).coerceIn(0f, 1f)
            if (expand > 0f) {
                val extra = labelGap + labelWidthOf(labelViews[i]) + labelEndPadding
                widths[i] += extra * expand
            }
        }

        val used = widths.sum()
        val spare = content - used
        if (spare > 0f) {
            val share = spare / count
            for (i in 0 until count) widths[i] += share
        } else if (spare < 0f) {
            // Only on a very narrow window: shrink proportionally rather than
            // letting the last slot run past the edge.
            val scale = content / used
            for (i in 0 until count) widths[i] *= scale
        }
        return widths
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(barHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height)

        val widths = slotWidths(contentWidthOf(width))
        itemViews.forEachIndexed { i, child ->
            child.measure(
                MeasureSpec.makeMeasureSpec(widths[i].roundToInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(innerHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutSlots()
    }

    /**
     * Positions the slots for the current progress.
     *
     * Called directly from the spring's setter rather than through requestLayout():
     * a full measure/layout pass every animation frame is what made the shift
     * stutter on high refresh rate screens.
     */
    private fun layoutSlots() {
        if (width == 0) return
        val widths = slotWidths(contentWidth())
        var x = outerInset
        val top = (height - innerHeight) / 2
        itemViews.forEachIndexed { i, child ->
            val left = x.roundToInt()
            val w = widths[i].roundToInt()
            child.layout(left, top, left + w, top + innerHeight)
            x += w
        }
    }

    // ---- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The surface itself comes from the background drawable, which is what
        // gives elevation its rounded outline; only the pill is painted here.
        val widths = slotWidths(contentWidth())

        val lower = floor(progress).toInt().coerceIn(destinations.indices)
        val upper = (lower + 1).coerceAtMost(destinations.lastIndex)
        val fraction = (progress - lower).coerceIn(0f, 1f)

        var before = outerInset
        for (i in 0 until lower) before += widths[i]

        val lowerWidth = widths[lower]
        val upperWidth = widths[upper]
        val pillLeft = before + lowerWidth * fraction
        val pillWidth = lowerWidth + (upperWidth - lowerWidth) * fraction

        val pillTop = (height - innerHeight) / 2f + pillInset
        val pillBottom = (height + innerHeight) / 2f - pillInset
        val radius = (pillBottom - pillTop) / 2f

        indicatorRect.set(
            pillLeft + pillInset,
            pillTop,
            pillLeft + pillWidth - pillInset,
            pillBottom
        )
        canvas.drawRoundRect(indicatorRect, radius, radius, indicatorPaint)
    }

    // ---- animation ----------------------------------------------------------

    private fun setProgress(value: Float) {
        progress = value.coerceIn(0f, destinations.lastIndex.toFloat())
        layoutSlots()
        updateAnimatedChildren()
        invalidate()
    }

    private fun updateAnimatedChildren() {
        destinations.indices.forEach { i ->
            val expand = (1f - abs(progress - i)).coerceIn(0f, 1f)
            // ease the emphasis so the icon does not snap at the start of a move
            val emphasis = expand * expand * (3f - 2f * expand)

            iconViews[i].apply {
                val scale = 1f + 0.10f * emphasis
                scaleX = scale
                scaleY = scale
                imageTintList = ColorStateList.valueOf(
                    if (i == selected) selectedContentColor else unselectedContentColor
                )
            }

            labelViews[i].apply {
                alpha = ((expand - 0.35f) / 0.65f).coerceIn(0f, 1f)
                translationX = (1f - expand) * dp(16f)
                setTextColor(selectedContentColor)
            }
        }
    }

    override fun onDetachedFromWindow() {
        spring?.cancel()
        spring = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** Smooth rather than snappy: a 300-400 ms glide with almost no overshoot. */
        private const val STIFFNESS = 170f
        private const val DAMPING = 0.88f

        private val PROGRESS_PROPERTY = object : FloatPropertyCompat<Material3FloatingNavBarView>("navProgress") {
            override fun getValue(view: Material3FloatingNavBarView): Float = view.progress
            override fun setValue(view: Material3FloatingNavBarView, value: Float) = view.setProgress(value)
        }
    }
}
