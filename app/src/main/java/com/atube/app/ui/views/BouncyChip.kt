package com.atube.app.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * A quality chip that answers with its own body instead of a ripple.
 *
 * Everything is driven by one spring: the scale. Pressing compresses it to 0.94
 * with a stiff, well damped spring; letting go while selected overshoots to 1.06
 * with almost no damping and then settles back to 1.0 with a softer one, which is
 * what reads as a bounce rather than simply growing. The colour, text weight and
 * the leading icon ride along on their own, slightly softer springs, so the icon
 * lands a beat behind the chip.
 */
class BouncyChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    // Resolved from the current theme, with plain fallbacks so a theme that does not
    // define one of these still yields a usable chip.
    private var selectedColor: Int = themeColor(android.R.attr.colorPrimary, Color.BLACK)
    private var onSelectedColor: Int = Color.WHITE
    private var unselectedColor: Int =
        themeColor(android.R.attr.colorBackground, Color.WHITE)
    private var outlineColor: Int =
        themeColor(android.R.attr.textColorSecondary, Color.GRAY)
    private var textColor: Int =
        themeColor(android.R.attr.textColorPrimary, Color.BLACK)


    private fun themeColor(attr: Int, fallback: Int): Int = runCatching {
        val value = android.util.TypedValue()
        if (context.theme.resolveAttribute(attr, value, true)) value.data else fallback
    }.getOrDefault(fallback)

    private val density = resources.displayMetrics.density
    private val backgroundShape = GradientDrawable().apply {
        cornerRadius = 14f * density
    }

    private val scaleHolder = FloatValueHolder(1f)
    private val scaleSpring = SpringAnimation(scaleHolder).apply {
        addUpdateListener { _, value, _ ->
            scaleX = value
            scaleY = value
        }
    }

    private val colourHolder = FloatValueHolder(0f)
    private val colourSpring = SpringAnimation(colourHolder).apply {
        spring.dampingRatio = 0.75f
        spring.stiffness = STIFFNESS_MEDIUM_LOW
        addUpdateListener { _, value, _ -> applyColours(value) }
    }

    private val iconHolder = FloatValueHolder(1f)
    private val iconSpring = SpringAnimation(iconHolder).apply {
        spring.dampingRatio = 0.45f
        spring.stiffness = STIFFNESS_MEDIUM
        addUpdateListener { _, value, _ -> applyIconScale(value) }
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        runCatching {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = backgroundShape
            setPadding(
                (16 * density).toInt(), (9 * density).toInt(),
                (16 * density).toInt(), (9 * density).toInt()
            )
            minHeight = (38 * density).toInt()
            textSize = 13f
            // painted after layout: touching the drawable during construction is what
            // could throw before the view was fully built
            post { runCatching { applyColours(if (isSelected) 1f else 0f) } }
        }
    }

    override fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        super.setSelected(selected)
        colourSpring.animateToFinalPosition(if (selected) 1f else 0f)
        iconSpring.animateToFinalPosition(if (selected) 1f else 0.8f)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        if (!selected) settle(0.62f, STIFFNESS_MEDIUM_LOW)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            scaleSpring.cancel()
            scaleX = 1f
            scaleY = 1f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> spring(0.94f, 0.60f, STIFFNESS_HIGH)
            MotionEvent.ACTION_UP -> {
                if (isSelected) bounce() else settle(0.62f, STIFFNESS_MEDIUM_LOW)
            }
            MotionEvent.ACTION_CANCEL -> settle(0.62f, STIFFNESS_MEDIUM_LOW)
        }
        return super.onTouchEvent(event)
    }

    /** The two-step bounce: overshoot to 1.06, then settle back to 1.0. */
    private fun bounce() {
        spring(1.06f, 0.34f, STIFFNESS_MEDIUM)
        postDelayed({ settle(0.62f, STIFFNESS_MEDIUM_LOW) }, 90L)
    }

    private fun settle(damping: Float, stiffness: Float) = spring(1f, damping, stiffness)

    private fun spring(target: Float, damping: Float, stiffness: Float) {
        scaleSpring.cancel()
        scaleSpring.setStartValue(scaleX)
        scaleSpring.spring.dampingRatio = damping
        scaleSpring.spring.stiffness = stiffness
        scaleSpring.animateToFinalPosition(target)
    }

    private fun applyColours(fraction: Float) = runCatching {
        val bg = ColorUtils.blendARGB(unselectedColor, selectedColor, fraction)
        backgroundShape.setColor(bg)
        backgroundShape.setStroke(
            (1 * density).toInt(),
            ColorUtils.setAlphaComponent(outlineColor, (255 * 0.6f * (1f - fraction)).toInt())
        )
        setTextColor(
            ColorUtils.blendARGB(
                textColor, onSelectedColor, fraction
            )
        )
        invalidate()
    }

    private fun applyIconScale(scale: Float) = runCatching {
        val icon = compoundDrawablesRelative.firstOrNull() ?: return@runCatching
        val size = (18 * density * scale).toInt().coerceAtLeast(1)
        icon.setBounds(0, 0, size, size)
    }.let { }

    private companion object {
        const val STIFFNESS_HIGH = 10_000f
        const val STIFFNESS_MEDIUM = 1_500f
        const val STIFFNESS_MEDIUM_LOW = 400f
    }
}
