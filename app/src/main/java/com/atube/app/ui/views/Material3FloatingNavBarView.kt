package com.atube.app.ui.views

import com.atube.app.R

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Floating pill navigation bar.
 *
 * Every destination is drawn the way the Material 3 navigation bar draws them -
 * the icon on top, its label underneath, both always visible - and the active
 * indicator is a pill that sits behind the selected icon. The previous version
 * only revealed the label of the selected item, which left the bar looking like
 * two bare icons with a stray word next to one of them.
 *
 * Slot widths are static, so a selection only moves the pill: no child is
 * re-measured or re-laid out while the spring runs.
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
        Destination("Downloads", R.drawable.ic_nav_downloader),
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
    private var velocity = 0f
    var onItemSelected: ((Int) -> Unit)? = null

    // ---- geometry -----------------------------------------------------------
    private val barHeight = dp(72f).toInt()
    private val innerHeight = dp(56f).toInt()
    private val iconSize = dp(24f).toInt()
    private val labelHeight = dp(16f).toInt()
    private val labelGapV = dp(3f)
    /** Smallest slot: wide enough to hold the indicator pill with a little air. */
    private val minSlotWidth = dp(56f)
    private val labelSidePadding = dp(10f)
    private val outerInset = dp(6f)
    private val pillWidth = dp(54f)
    private val pillHeight = dp(30f)
    private val cornerRadius = dp(36f)
    private val labelTextSize = 11.5f

    /** Total height of the icon + gap + label block, used to centre the pill. */
    private val contentHeight get() = iconSize + labelGapV + labelHeight

    /**
     * One spring for the lifetime of the view, driven through a FloatValueHolder
     * and retargeted with animateToFinalPosition(), which keeps the velocity the
     * spring already has - so a second tap mid flight bends instead of snapping.
     */
    private val progressHolder = FloatValueHolder(0f)
    private val spring = SpringAnimation(progressHolder).apply {
        spring = SpringForce(0f).apply {
            // under-damped on purpose: overshoots its slot, settles back, stops
            dampingRatio = DAMPING
            stiffness = STIFFNESS
        }
        addUpdateListener { _: DynamicAnimation<*>, value: Float, v: Float ->
            setProgress(value, v)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        elevation = dp(10f)
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = this@Material3FloatingNavBarView.cornerRadius
        }

        destinations.forEachIndexed { index, destination ->
            val slot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
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
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val label = TextView(context).apply {
                text = destination.label
                textSize = labelTextSize
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(if (index == selected) selectedContentColor else unselectedContentColor)
                layoutParams = LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, labelHeight
                ).apply { topMargin = labelGapV.roundToInt() }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            slot.addView(icon)
            slot.addView(label)
            addView(slot)
            itemViews += slot
            iconViews += icon
            labelViews += label
        }

        contentDescription = "Bottom navigation"
    }

    /** Width the label needs, measured with the paint the TextView draws with. */
    private fun labelWidthOf(label: TextView): Float =
        label.paint.measureText(label.text.toString()) + dp(2f)

    @JvmOverloads
    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        select(index, notify = false, animate = animate)
    }

    fun getSelectedIndex(): Int = selected

    private fun select(index: Int, notify: Boolean, animate: Boolean = true) {
        val safeIndex = index.coerceIn(destinations.indices)

        if (safeIndex == selected && abs(progress - safeIndex) < 0.001f) {
            if (notify) onItemSelected?.invoke(safeIndex)
            return
        }

        selected = safeIndex
        itemViews.forEachIndexed { i, view ->
            view.isSelected = i == selected
            view.contentDescription =
                if (i == selected) "${destinations[i].label}, selected" else destinations[i].label
        }

        if (animate) {
            spring.animateToFinalPosition(safeIndex.toFloat())
        } else {
            spring.cancel()
            setProgress(safeIndex.toFloat(), 0f)
        }

        if (notify) onItemSelected?.invoke(safeIndex)
    }

    // ---- measurement --------------------------------------------------------

    private fun contentWidthOf(viewWidth: Int): Float =
        (viewWidth - 2 * outerInset).coerceAtLeast(1f)

    /**
     * Static slot widths: the widest of the minimum slot and the label plus its
     * side padding, with the leftover shared out evenly so the items sit centred
     * rather than bunched at the ends. No label can be clipped, because the slot
     * is always at least as wide as the text it holds.
     */
    private fun slotWidths(content: Float): FloatArray {
        val count = destinations.size
        val widths = FloatArray(count) { i ->
            max(minSlotWidth, labelWidthOf(labelViews[i]) + 2 * labelSidePadding)
        }

        val used = widths.sum()
        val spare = content - used
        if (spare > 0f) {
            val share = spare / count
            for (i in 0 until count) widths[i] += share
        } else if (spare < 0f) {
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
        val widths = slotWidths(contentWidthOf(width))
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
        // The surface comes from the background drawable, which also gives
        // elevation its rounded outline; only the indicator is painted here.
        if (width == 0) return

        val widths = slotWidths(contentWidthOf(width))
        val centres = FloatArray(destinations.size)
        var x = outerInset
        for (i in destinations.indices) {
            centres[i] = x + widths[i] / 2f
            x += widths[i]
        }

        val lower = floor(progress).toInt().coerceIn(destinations.indices)
        val upper = (lower + 1).coerceAtMost(destinations.lastIndex)
        val fraction = (progress - lower).coerceIn(0f, 1f)
        val centreX = centres[lower] + (centres[upper] - centres[lower]) * fraction

        // vertical: the pill hugs the icon, not the whole slot
        val slotTop = (height - innerHeight) / 2f
        val contentTop = slotTop + (innerHeight - contentHeight) / 2f
        val iconCentreY = contentTop + iconSize / 2f
        val pillTop = iconCentreY - pillHeight / 2f
        val pillBottom = iconCentreY + pillHeight / 2f
        val radius = pillHeight / 2f

        // stretch along the direction of travel, then pull back as it settles
        val stretch = (abs(velocity) * STRETCH_FACTOR).coerceAtMost(MAX_STRETCH) * pillWidth
        val halfWidth = (pillWidth + stretch) / 2f

        var left = centreX - halfWidth
        var right = centreX + halfWidth
        val minLeft = outerInset
        val maxRight = width - outerInset
        if (left < minLeft) {
            right += minLeft - left
            left = minLeft
        }
        if (right > maxRight) {
            left -= right - maxRight
            right = maxRight
        }

        indicatorRect.set(
            left.coerceAtLeast(minLeft),
            pillTop,
            right.coerceAtMost(maxRight),
            pillBottom
        )
        canvas.drawRoundRect(indicatorRect, radius, radius, indicatorPaint)
    }

    // ---- animation ----------------------------------------------------------

    private fun setProgress(value: Float, v: Float = 0f) {
        velocity = v
        progress = value.coerceIn(0f, destinations.lastIndex.toFloat())
        updateAnimatedChildren()
        invalidate()
    }

    private fun updateAnimatedChildren() {
        destinations.indices.forEach { i ->
            val expand = (1f - abs(progress - i)).coerceIn(0f, 1f)
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
                alpha = 0.72f + 0.28f * emphasis
                setTextColor(
                    if (i == selected) selectedContentColor else unselectedContentColor
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Under-damped on purpose: 0.55 overshoots the slot and eases back. */
        private const val STIFFNESS = 240f
        private const val DAMPING = 0.55f

        /** How far the pill stretches horizontally per unit of travel speed. */
        private const val STRETCH_FACTOR = 0.0018f
        private const val MAX_STRETCH = 0.30f
    }
}
