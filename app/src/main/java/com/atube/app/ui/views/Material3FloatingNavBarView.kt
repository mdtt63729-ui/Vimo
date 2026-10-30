package com.atube.app.ui.views

import com.atube.app.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.floor

/**
 * Native Android View implementation of the approved four-destination floating bar.
 * One spring-driven progress value controls slot widths, the moving pill, icon motion,
 * and label reveal so the elements stay synchronized.
 */
class Material3FloatingNavBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    data class Destination(
        val label: String,
        val iconRes: Int
    )

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

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surfaceColor }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = indicatorColor }
    private val indicatorRect = RectF()
    private val itemViews = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private val labelWidths = destinations.map { label ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }.measureText(label.label) / density
    }

    private var progress = 0f
    private var selected = 0
    private var spring: SpringAnimation? = null
    var onItemSelected: ((Int) -> Unit)? = null

    private val barHeight = dp(60f).toInt()
    private val innerHeight = dp(44f).toInt()
    private val iconSlot = dp(56f)
    private val labelGap = dp(8f)
    private val outerInset = dp(8f)
    private val cornerRadius = dp(30f)
    private val indicatorRadius = dp(22f)

    init {
        setWillNotDraw(false)
        isClickable = false
        elevation = dp(8f)
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
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (index == selected) selectedContentColor else unselectedContentColor
                )
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(iconSlot.toInt(), innerHeight)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = destination.label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(selectedContentColor)
                alpha = if (index == 0) 1f else 0f
                translationX = if (index == 0) 0f else dp(30f)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            slot.addView(icon)
            slot.addView(label, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, innerHeight
            ).apply { marginStart = labelGap.toInt() })
            addView(slot)
            itemViews += slot
            iconViews += icon
            labelViews += label
        }
        contentDescription = "Bottom navigation"
    }

    /** Selects a destination. The selected index is clamped to the four available items. */
    @JvmOverloads
    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        select(index, notify = false, animate = animate)
    }

    fun getSelectedIndex(): Int = selected

    private fun select(index: Int, notify: Boolean, animate: Boolean = true) {
        val safeIndex = index.coerceIn(destinations.indices)
        if (safeIndex == selected && progress == safeIndex.toFloat()) return
        selected = safeIndex
        itemViews.forEachIndexed { i, view ->
            view.isSelected = i == selected
            view.contentDescription = if (i == selected) "${destinations[i].label}, selected" else destinations[i].label
        }
        spring?.cancel()
        if (animate) {
            spring = SpringAnimation(this, PROGRESS_PROPERTY).apply {
                spring = SpringForce(safeIndex.toFloat()).apply {
                    dampingRatio = 0.78f
                    stiffness = STIFFNESS
                }
                setStartVelocity(0f)
                start()
            }
        } else {
            progress = safeIndex.toFloat()
            updateAnimatedChildren()
            requestLayout()
            invalidate()
        }
        if (notify) onItemSelected?.invoke(safeIndex)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = barHeight
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        val contentWidth = (width - 2 * outerInset).coerceAtLeast(0f)
        val widths = calculateSlotWidths(contentWidth)
        itemViews.forEachIndexed { i, child ->
            val childWidth = widths.getOrElse(i) { 0f }.toInt().coerceAtLeast(0)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(innerHeight, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val widths = calculateSlotWidths((width - 2 * outerInset).coerceAtLeast(0f))
        var x = outerInset.toInt()
        itemViews.forEachIndexed { i, child ->
            val w = widths.getOrElse(i) { 0f }.toInt()
            val top = (height - innerHeight) / 2
            child.layout(x, top, x + w, top + innerHeight)
            x += w
        }
        updateAnimatedChildren()
        // the pill is drawn in onDraw from the same progress value, nothing to update here
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, surfacePaint
        )
        val lower = floor(progress).toInt().coerceIn(destinations.indices)
        val upper = (lower + 1).coerceAtMost(destinations.lastIndex)
        val fraction = (progress - lower).coerceIn(0f, 1f)
        val widths = calculateSlotWidths((width - 2 * outerInset).coerceAtLeast(0f))
        var before = outerInset
        for (i in 0 until lower) before += widths.getOrElse(i) { 0f }
        val lowerWidth = widths.getOrElse(lower) { 0f }
        val upperWidth = widths.getOrElse(upper) { lowerWidth }
        val pillLeft = before + lowerWidth * fraction
        val pillWidth = lowerWidth + (upperWidth - lowerWidth) * fraction
        val pillTop = (height - innerHeight) / 2f
        indicatorRect.set(pillLeft, pillTop, pillLeft + pillWidth, pillTop + innerHeight)
        canvas.drawRoundRect(indicatorRect, indicatorRadius, indicatorRadius, indicatorPaint)
    }

    private fun calculateSlotWidths(contentWidth: Float): List<Float> {
        val expand = destinations.indices.map { i -> (1f - abs(progress - i)).coerceIn(0f, 1f) }
        val weights = destinations.indices.map { i ->
            iconSlot + (labelWidths[i] + labelGap / density) * density * expand[i]
        }
        val total = weights.sum().takeIf { it > 0f } ?: 1f
        return weights.map { contentWidth * (it / total) }
    }

    private fun updateAnimatedChildren() {
        val expand = destinations.indices.map { i -> (1f - abs(progress - i)).coerceIn(0f, 1f) }
        destinations.indices.forEach { i ->
            val emphasis = expand[i] * (2f - expand[i])
            iconViews[i].apply {
                val scale = 1f + 0.12f * emphasis
                scaleX = scale
                scaleY = scale
                rotation = -7f * emphasis
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (i == selected) selectedContentColor else unselectedContentColor
                )
            }
            labelViews[i].apply {
                alpha = ((expand[i] - 0.3f) / 0.7f).coerceIn(0f, 1f)
                translationX = (1f - expand[i]) * dp(30f)
                setTextColor(selectedContentColor)
            }
        }
    }

    private fun setProgress(value: Float) {
        progress = value.coerceIn(0f, destinations.lastIndex.toFloat())
        // Lay the slots out here rather than calling requestLayout(): a full layout
        // pass every animation frame is what made the shift stutter on 120 Hz.
        layoutSlots()
        updateAnimatedChildren()
        invalidate()
    }

    /** Positions the slots for the current progress without a full layout pass. */
    private fun layoutSlots() {
        if (width == 0) return
        val widths = calculateSlotWidths((width - 2 * outerInset).coerceAtLeast(0f))
        var x = outerInset.toInt()
        val top = (height - innerHeight) / 2
        itemViews.forEachIndexed { i, child ->
            val w = widths.getOrElse(i) { 0f }.toInt()
            child.layout(x, top, x + w, top + innerHeight)
            x += w
        }
    }

    override fun onDetachedFromWindow() {
        spring?.cancel()
        spring = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** The PRD's spring stiffness for the selection movement. */
        private const val STIFFNESS = 190f

        private val PROGRESS_PROPERTY = object : androidx.dynamicanimation.animation.FloatPropertyCompat<Material3FloatingNavBarView>("navProgress") {
            override fun getValue(view: Material3FloatingNavBarView): Float = view.progress
            override fun setValue(view: Material3FloatingNavBarView, value: Float) = view.setProgress(value)
        }
    }
}
