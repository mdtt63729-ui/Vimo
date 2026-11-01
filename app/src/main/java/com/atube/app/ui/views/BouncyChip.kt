package com.atube.app.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * A quality chip that answers with its own body instead of a ripple.
 *
 * Deliberately a plain TextView, and deliberately lazy: nothing is created or read
 * from the theme in the constructor. An earlier version resolved theme colours while
 * the object was still being built, and constructing it from an animation callback
 * threw a NullPointerException before the body ever ran. Everything here is created
 * on first attach, and every animation is guarded.
 */
class BouncyChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {

    private var selectedColor: Int = Color.DKGRAY
    private var onSelectedColor: Int = Color.WHITE
    private var unselectedColor: Int = Color.LTGRAY
    private var outlineColor: Int = Color.GRAY
    private var normalTextColor: Int = Color.BLACK

    private var density: Float = 2f
    private var backgroundShape: GradientDrawable? = null

    private var scaleSpring: SpringAnimation? = null
    private var colourSpring: SpringAnimation? = null
    private var iconSpring: SpringAnimation? = null

    private var ready = false

    init {
        runCatching {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ready) return
        runCatching { prepare() }
    }

    private fun prepare() {
        density = resources.displayMetrics.density
        val theme = context.theme

        fun resolve(attr: Int, fallback: Int): Int = runCatching {
            val value = android.util.TypedValue()
            if (theme.resolveAttribute(attr, value, true)) value.data else fallback
        }.getOrDefault(fallback)

        selectedColor = resolve(android.R.attr.colorPrimary, Color.DKGRAY)
        onSelectedColor = Color.WHITE
        unselectedColor = resolve(android.R.attr.colorBackground, Color.LTGRAY)
        outlineColor = resolve(android.R.attr.textColorSecondary, Color.GRAY)
        normalTextColor = resolve(android.R.attr.textColorPrimary, Color.BLACK)

        val shape = GradientDrawable().apply { cornerRadius = 14f * density }
        backgroundShape = shape
        background = shape

        setPadding(
            (16 * density).toInt(), (9 * density).toInt(),
            (16 * density).toInt(), (9 * density).toInt()
        )
        minHeight = (38 * density).toInt()
        textSize = 13f

        scaleSpring = SpringAnimation(FloatValueHolder(1f)).apply {
            addUpdateListener { _, value, _ ->
                runCatching {
                    scaleX = value
                    scaleY = value
                }
            }
        }
        colourSpring = SpringAnimation(FloatValueHolder(if (isSelected) 1f else 0f)).apply {
            spring.dampingRatio = 0.75f
            spring.stiffness = STIFFNESS_MEDIUM_LOW
            addUpdateListener { _, value, _ -> runCatching { paint(value) } }
        }
        iconSpring = SpringAnimation(FloatValueHolder(1f)).apply {
            spring.dampingRatio = 0.45f
            spring.stiffness = STIFFNESS_MEDIUM
            addUpdateListener { _, value, _ -> runCatching { paintIcon(value) } }
        }

        ready = true
        runCatching { paint(if (isSelected) 1f else 0f) }
    }

    override fun setSelected(selected: Boolean) {
        if (isSelected == selected) return
        super.setSelected(selected)
        if (!ready) return
        runCatching {
            colourSpring?.animateToFinalPosition(if (selected) 1f else 0f)
            iconSpring?.animateToFinalPosition(if (selected) 1f else 0.8f)
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            if (!selected) spring(1f, 0.62f, STIFFNESS_MEDIUM_LOW)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled && ready) runCatching {
            scaleSpring?.cancel()
            scaleX = 1f
            scaleY = 1f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !ready) return super.onTouchEvent(event)
        runCatching {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> spring(0.94f, 0.60f, STIFFNESS_HIGH)
                MotionEvent.ACTION_UP ->
                    if (isSelected) bounce() else spring(1f, 0.62f, STIFFNESS_MEDIUM_LOW)
                MotionEvent.ACTION_CANCEL -> spring(1f, 0.62f, STIFFNESS_MEDIUM_LOW)
            }
        }
        return super.onTouchEvent(event)
    }

    /** The two-step bounce: overshoot to 1.06, then settle back to 1.0. */
    private fun bounce() {
        spring(1.06f, 0.34f, STIFFNESS_MEDIUM)
        postDelayed({ runCatching { spring(1f, 0.62f, STIFFNESS_MEDIUM_LOW) } }, 90L)
    }

    private fun spring(target: Float, damping: Float, stiffness: Float) {
        val animation = scaleSpring ?: return
        animation.cancel()
        animation.setStartValue(scaleX)
        animation.spring.dampingRatio = damping
        animation.spring.stiffness = stiffness
        animation.animateToFinalPosition(target)
    }

    private fun paint(fraction: Float) {
        val shape = backgroundShape ?: return
        shape.setColor(ColorUtils.blendARGB(unselectedColor, selectedColor, fraction))
        shape.setStroke(
            (1 * density).toInt(),
            ColorUtils.setAlphaComponent(outlineColor, (255 * 0.6f * (1f - fraction)).toInt())
        )
        setTextColor(ColorUtils.blendARGB(normalTextColor, onSelectedColor, fraction))
        invalidate()
    }

    private fun paintIcon(scale: Float) {
        val icon = compoundDrawablesRelative.firstOrNull() ?: return
        val size = (18 * density * scale).toInt().coerceAtLeast(1)
        icon.setBounds(0, 0, size, size)
    }

    private companion object {
        const val STIFFNESS_HIGH = 10_000f
        const val STIFFNESS_MEDIUM = 1_500f
        const val STIFFNESS_MEDIUM_LOW = 400f
    }
}
