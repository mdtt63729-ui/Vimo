package com.atube.app.ui.views

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A floating, pill-shaped navigation bar, ported from the Off app's Compose
 * `FloatingNavBar` into a plain View.
 *
 * One animated value — the fractional position of the selection — drives
 * everything: each slot's width, the label fades and the indicator itself. That is
 * what makes the shift seamless. Animating the slots and the pill separately lets
 * them drift a frame apart, and drawing a pill per item puts two of them on screen
 * mid-switch.
 *
 * The motion matches Off exactly: the selection position runs on a spring with a
 * 0.78 damping ratio at medium-low stiffness, and each icon dips to 0.92 with a
 * stiff spring on touch. An overshoot interpolator gives a different feel — it
 * bounces past the target instead of settling into it.
 */
class FloatingNavBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    /** Called with the menu item id of the tapped slot. */
    var onItemSelected: ((Int) -> Unit)? = null

    private val items = mutableListOf<FloatingNavItem>()
    private val slots = mutableListOf<SlotView>()
    private val badgeCounts = mutableMapOf<Int, Int>()

    private val pill = View(context)
    private val pillDrawable = GradientDrawable()

    private val argbEvaluator = ArgbEvaluator()

    /** Animated selection position, e.g. 1.4 means 40% of the way from slot 1 to 2. */
    private var progress = 0f

    private val progressHolder = FloatValueHolder(0f)
    private var progressSpring: SpringAnimation? = null

    private val iconSlotPx = dp(56f)
    private val labelGapPx = dp(8f)
    private val pillHeightPx = dp(44f)
    private val barPaddingPx = dp(8f)
    private val iconSizePx = dp(22f)
    private val labelSlidePx = dp(30f)

    private val colorSurfaceContainerHigh = themeColor(
        com.google.android.material.R.attr.colorSurfaceContainerHigh,
        0xFF2A2A2E.toInt()
    )
    private val colorPrimaryContainer = themeColor(
        com.google.android.material.R.attr.colorPrimaryContainer,
        0xFF4A4458.toInt()
    )
    private val colorOnPrimaryContainer = themeColor(
        com.google.android.material.R.attr.colorOnPrimaryContainer,
        0xFFE8DEF8.toInt()
    )
    private val colorOnSurfaceVariant = themeColor(
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        0xFFCAC4D0.toInt()
    )
    private val colorBadge = themeColor(
        androidx.appcompat.R.attr.colorPrimary,
        0xFF6750A4.toInt()
    )
    private val colorBadgeText = themeColor(
        com.google.android.material.R.attr.colorOnPrimary,
        0xFFFFFFFF.toInt()
    )

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorBadge }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBadgeText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = dp(10f)
    }
    private val badgeRect = RectF()

    init {
        clipChildren = true
        clipToPadding = false
        setWillNotDraw(false)

        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(30f)
            setColor(colorSurfaceContainerHigh)
        }
        // The rounded background doubles as the shadow outline.
        elevation = dp(14f)
        clipToOutline = true

        pillDrawable.shape = GradientDrawable.RECTANGLE
        pillDrawable.cornerRadius = pillHeightPx / 2f
        pillDrawable.setColor(colorPrimaryContainer)
        pill.background = pillDrawable
        addView(pill)
    }

    /* ------------------------------ public API ------------------------------ */

    fun setItems(newItems: List<FloatingNavItem>, selectedItemId: Int?) {
        items.clear()
        items.addAll(newItems)
        rebuildSlots()

        val index = newItems.indexOfFirst { it.itemId == selectedItemId }
        progress = (if (index >= 0) index else 0).toFloat()
        progressHolder.setValue(progress)
        applySlotStyles()
        requestLayout()
    }

    /** Moves the pill to the given item, animating if the bar is already on screen. */
    fun setSelectedItem(itemId: Int, animate: Boolean = true) {
        val index = items.indexOfFirst { it.itemId == itemId }
        if (index < 0) return
        if (progress.roundToInt() == index && progressSpring?.isRunning != true) return
        animateTo(index.toFloat(), animate)
    }

    fun setBadgeCount(itemId: Int, count: Int) {
        if (count <= 0) badgeCounts.remove(itemId) else badgeCounts[itemId] = count
        invalidate()
    }

    fun clearBadge(itemId: Int) {
        badgeCounts.remove(itemId)
        invalidate()
    }

    /* ------------------------------- internals ------------------------------ */

    private fun rebuildSlots() {
        slots.forEach { removeView(it) }
        slots.clear()

        items.forEach { item ->
            val slot = SlotView(context, item, iconSlotPx, labelGapPx, iconSizePx)
            slot.setOnClickListener { onItemSelected?.invoke(item.itemId) }
            slots.add(slot)
            addView(slot)
        }
    }

    private fun animateTo(target: Float, animate: Boolean) {
        progressSpring?.cancel()

        if (!animate || !isAttachedToWindow) {
            progress = target
            progressHolder.setValue(target)
            applySlotStyles()
            requestLayout()
            invalidate()
            return
        }

        progressHolder.setValue(progress)
        progressSpring = SpringAnimation(progressHolder).apply {
            // Same spring as Off: enough bounce to feel alive, not so much that the
            // label overshoots its slot.
            spring = SpringForce(target)
                .setDampingRatio(SPRING_DAMPING)
                .setStiffness(SPRING_STIFFNESS)
            addUpdateListener { _, value, _ ->
                progress = value
                applySlotStyles()
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    /** How far open each slot is, as a smooth triangle centred on the selection. */
    private fun expansions(): List<Float> = items.indices.map { i ->
        (1f - abs(progress - i)).coerceIn(0f, 1f)
    }

    private fun applySlotStyles() {
        val expand = expansions()
        slots.forEachIndexed { index, slot ->
            val open = expand.getOrElse(index) { 0f }
            val contentColor = argbEvaluator.evaluate(
                open,
                colorOnSurfaceVariant,
                colorOnPrimaryContainer
            ) as Int
            slot.applyContentColor(contentColor)
            slot.label.alpha = ((open - 0.3f) / 0.7f).coerceIn(0f, 1f)
            slot.label.translationX = (1f - open) * labelSlidePx
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (pillHeightPx + 2 * barPaddingPx).roundToInt()
        setMeasuredDimension(width, height)

        val available = (width - 2 * barPaddingPx).coerceAtLeast(1f)
        val slotWidths = computeSlotWidths(available)

        pill.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pillHeightPx.roundToInt(), MeasureSpec.EXACTLY)
        )

        slots.forEachIndexed { index, slot ->
            slot.measure(
                MeasureSpec.makeMeasureSpec(slotWidths[index].roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(pillHeightPx.roundToInt(), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (items.isEmpty()) return

        val width = (r - l).toFloat()
        val height = (b - t).toFloat()
        val available = (width - 2 * barPaddingPx).coerceAtLeast(1f)
        val slotWidths = computeSlotWidths(available)

        val top = ((height - pillHeightPx) / 2f).roundToInt()

        // The pill interpolates between the two slots the selection sits between.
        val lower = floor(progress).toInt().coerceIn(0, items.lastIndex)
        val upper = (lower + 1).coerceAtMost(items.lastIndex)
        val fraction = (progress - lower).coerceIn(0f, 1f)

        var x = barPaddingPx
        for (i in slots.indices) {
            val slotWidth = slotWidths[i]
            slots[i].layout(
                x.roundToInt(),
                top,
                (x + slotWidth).roundToInt(),
                top + pillHeightPx.roundToInt()
            )
            x += slotWidth
        }

        var before = barPaddingPx
        for (i in 0 until lower) before += slotWidths[i]
        val pillX = before + slotWidths[lower] * fraction
        val pillWidth = slotWidths[lower] + (slotWidths[upper] - slotWidths[lower]) * fraction
        pill.layout(
            pillX.roundToInt(),
            top,
            (pillX + pillWidth).roundToInt(),
            top + pillHeightPx.roundToInt()
        )
    }

    private fun computeSlotWidths(available: Float): List<Float> {
        if (items.isEmpty()) return emptyList()
        val expand = expansions()
        // Icon slot plus the label's natural width, opened up only while selected.
        val weights = items.indices.map { i ->
            ICON_SLOT_DP + (slots.getOrNull(i)?.labelWidthDp()?.let { it + LABEL_GAP_DP } ?: 0f) * expand[i]
        }
        val total = weights.sum().takeIf { it > 0f } ?: 1f
        return weights.map { available * (it / total) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawBadges(canvas)
    }

    private fun drawBadges(canvas: Canvas) {
        if (badgeCounts.isEmpty() || slots.isEmpty()) return
        slots.forEachIndexed { index, slot ->
            val count = badgeCounts[items.getOrNull(index)?.itemId] ?: return@forEachIndexed
            val text = if (count > 99) "99+" else count.toString()
            val radius = dp(if (text.length > 2) 9f else 8f)
            val cx = slot.left + iconSlotPx / 2f + dp(8f)
            val cy = slot.top + dp(6f)
            badgeRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawRoundRect(badgeRect, radius, radius, badgePaint)
            val baseline = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(text, cx, baseline, badgeTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        progressSpring?.cancel()
        progressSpring = null
        super.onDetachedFromWindow()
    }

    private fun themeColor(attr: Int, fallback: Int): Int = try {
        MaterialColors.getColor(this, attr)
    } catch (_: Throwable) {
        fallback
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /* -------------------------------- slots -------------------------------- */

    private class SlotView(
        context: Context,
        val item: FloatingNavItem,
        private val iconSlotPx: Float,
        private val labelGapPx: Float,
        private val iconSizePx: Float
    ) : ViewGroup(context) {

        val icon = ImageView(context)
        val label = TextView(context)

        private var scaleXSpring: SpringAnimation? = null
        private var scaleYSpring: SpringAnimation? = null

        init {
            clipChildren = true
            isClickable = true
            isFocusable = true
            contentDescription = item.label

            icon.setImageDrawable(item.icon)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.isClickable = false

            label.text = item.label
            // Off uses Material's labelLarge: 14 sp, medium weight, a little tracking.
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            label.letterSpacing = 0.01f
            label.includeFontPadding = false
            label.isSingleLine = true
            label.ellipsize = TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER_VERTICAL
            label.isClickable = false

            addView(icon)
            addView(label)

            // Off's press dip: 0.92 while held, released on a stiff spring.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { springTo(0.92f); false }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { springTo(1f); false }
                    else -> false
                }
            }
        }

        private fun springTo(target: Float) {
            scaleXSpring?.cancel()
            scaleYSpring?.cancel()
            scaleXSpring = SpringAnimation(this, DynamicAnimation.SCALE_X, target).apply {
                spring = SpringForce(target)
                    .setDampingRatio(PRESS_DAMPING)
                    .setStiffness(SpringForce.STIFFNESS_HIGH)
                start()
            }
            scaleYSpring = SpringAnimation(this, DynamicAnimation.SCALE_Y, target).apply {
                spring = SpringForce(target)
                    .setDampingRatio(PRESS_DAMPING)
                    .setStiffness(SpringForce.STIFFNESS_HIGH)
                start()
            }
        }

        fun applyContentColor(color: Int) {
            icon.setColorFilter(color)
            label.setTextColor(color)
        }

        fun labelWidthDp(): Float =
            label.paint.measureText(label.text.toString()) / resources.displayMetrics.density

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(width, height)

            val iconSide = iconSizePx.roundToInt()
            icon.measure(
                MeasureSpec.makeMeasureSpec(iconSide, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(iconSide, MeasureSpec.EXACTLY)
            )

            val labelAvailable = (width - iconSlotPx - labelGapPx).coerceAtLeast(0f).roundToInt()
            label.measure(
                MeasureSpec.makeMeasureSpec(labelAvailable, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val height = b - t
            val iconSide = min(iconSizePx.roundToInt(), height)
            val iconLeft = ((min(iconSlotPx.toInt(), r - l) - iconSide) / 2f).roundToInt()
            val iconTop = ((height - iconSide) / 2f).roundToInt()
            icon.layout(iconLeft, iconTop, iconLeft + iconSide, iconTop + iconSide)

            val labelLeft = (iconSlotPx + labelGapPx).roundToInt()
            val labelWidth = min(label.measuredWidth, (r - l) - labelLeft)
            label.layout(
                labelLeft,
                0,
                labelLeft + labelWidth.coerceAtLeast(0),
                height
            )
        }
    }

    private companion object {
        const val ICON_SLOT_DP = 56f
        const val LABEL_GAP_DP = 8f

        // Off's springs: 0.78 damping at medium-low stiffness for the selection,
        // 0.40 at high stiffness for the press.
        const val SPRING_DAMPING = 0.78f
        const val SPRING_STIFFNESS = 400f
        const val PRESS_DAMPING = 0.40f
    }
}

/** One entry of the floating navigation bar. */
data class FloatingNavItem(
    val itemId: Int,
    val label: String,
    val icon: Drawable?
)
