package com.atube.app.ui.views

import com.atube.app.R

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
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

    // The PRD fixes the palette rather than taking it from the theme, so the bar
    // keeps its dark premium look in both light and dark mode.
    private val surfaceColor = 0xFF191917.toInt()      // container
    private val indicatorColor = 0xFF3B2B23.toInt()    // active capsule
    private val selectedContentColor = 0xFFFFAB88.toInt()
    private val unselectedContentColor = 0xFFBEB6AE.toInt()

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
    private val pillHeight = dp(32f)
    private val cornerRadius = dp(24f)
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
                // subtle press feedback, the way a premium bar answers a touch
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    }
                    false
                }
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

        if (animate && !animationsDisabled()) {
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

    /** Top of the 56dp content row, centred inside the padding box. */
    private fun slotTop(): Float {
        val available = (height - paddingTop - paddingBottom).coerceAtLeast(innerHeight)
        return paddingTop + (available - innerHeight) / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // The bar is handed a bottom padding equal to the navigation-bar inset plus
        // a small gap, and ViewGroup clips its children to the padding box. The
        // measured height has to include that padding, otherwise the content is
        // centred in the full height and the label row is cut off at the bottom.
        val height = resolveSize(barHeight + paddingTop + paddingBottom, heightMeasureSpec)
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
        val top = slotTop().toInt()
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
        val slotTop = slotTop()
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

            // The tint is blended along the same spring value that moves the
            // capsule, so the colour arrives with the pill instead of snapping the
            // moment the selection changes.
            val tint = blendColor(unselectedContentColor, selectedContentColor, emphasis)

            iconViews[i].apply {
                val scale = 1f + 0.10f * emphasis
                scaleX = scale
                scaleY = scale
                imageTintList = ColorStateList.valueOf(tint)
            }

            labelViews[i].apply {
                alpha = 0.72f + 0.28f * emphasis
                setTextColor(tint)
            }
        }
    }

    /** Linear blend of two ARGB colours; t runs 0..1. */
    private fun blendColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }

    /**
     * Android's "remove animations" accessibility setting, or a developer option
     * that scales animations to zero, should turn the spring off entirely.
     */
    private fun animationsDisabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)

    override fun onDetachedFromWindow() {
        spring.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** The PRD's spring: a quick, slightly under-damped glide. */
        private const val STIFFNESS = 420f
        private const val DAMPING = 0.78f

        /** How far the pill stretches horizontally per unit of travel speed. */
        private const val STRETCH_FACTOR = 0.0018f
        private const val MAX_STRETCH = 0.30f
    }
}
