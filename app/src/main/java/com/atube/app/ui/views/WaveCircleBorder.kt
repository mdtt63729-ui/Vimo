package com.atube.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Material 3-style animated circular wave border that rotates around a central
 * circular image. Draws multiple smooth sinusoidal wave paths that orbit the
 * center, creating a coral-like flowing effect.
 *
 * The view should be sized slightly larger than the inner image so the waves
 * are visible around the edges. Place an ImageView on top centered within.
 */
class WaveCircleBorder @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
        alpha = 40
    }

    // Wave configuration
    private var rotation = 0f
    private val waveCount = 3
    private val waveAmplitude = 14f
    private val waveFrequency = 6f
    private val wavePoints = 120

    // Material 3 color palette — using the app's dynamic colors
    private val waveColors = intArrayOf(
        Color.parseColor("#0061A4"), // Primary
        Color.parseColor("#6750A4"), // Secondary
        Color.parseColor("#7D5260"), // Tertiary
    )

    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 12000 // 12 seconds per full rotation
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
        }
        animator?.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - waveAmplitude - 8f

        if (radius <= 0) return

        // Draw glow rings (soft, behind the waves)
        for (i in 0 until waveCount) {
            glowPaint.color = waveColors[i % waveColors.size]
            val r = radius + 6f + i * 3f
            canvas.drawCircle(cx, cy, r, glowPaint)
        }

        // Draw animated wave paths
        for (waveIndex in 0 until waveCount) {
            wavePaint.color = waveColors[waveIndex % waveColors.size]
            val phaseOffset = waveIndex * (360f / waveCount)
            val path = buildWavePath(cx, cy, radius - waveIndex * 4f, rotation + phaseOffset)
            canvas.drawPath(path, wavePaint)
        }
    }

    /**
     * Build a smooth sinusoidal wave path that circles the center.
     * The wave radius oscillates around [baseRadius] with amplitude [waveAmplitude].
     */
    private fun buildWavePath(
        cx: Float,
        cy: Float,
        baseRadius: Float,
        rotationDeg: Float
    ): Path {
        val path = Path()
        val rotationRad = rotationDeg * PI.toFloat() / 180f

        for (i in 0..wavePoints) {
            val angle = (i.toFloat() / wavePoints) * 2f * PI.toFloat()
            val waveOffset = waveAmplitude * sin(
                (angle * waveFrequency + rotationRad).toDouble()
            ).toFloat()
            val r = baseRadius + waveOffset
            val x = cx + r * cos(angle.toDouble()).toFloat()
            val y = cy + r * sin(angle.toDouble()).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        return path
    }
}
