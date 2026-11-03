package com.atube.app.ui.effects

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import java.util.Collections
import java.util.WeakHashMap

/**
 * Adds a consistent, theme-aware touch response to clickable Android Views.
 * Existing Material ripples, custom foregrounds and custom state animators are
 * deliberately preserved to avoid double ripples or breaking bespoke controls.
 */
class GlobalTouchEffects private constructor() : Application.ActivityLifecycleCallbacks {

    private val observedRoots = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val styledViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        val root = activity.window?.decorView ?: return
        root.post {
            applyToTree(root)
            if (observedRoots.put(root, true) == null) {
                // Covers controls inflated later (dialogs, menus, and recycled rows).
                // Styled views are memoized, so subsequent passes only touch new views.
                root.viewTreeObserver.addOnGlobalLayoutListener { applyToTree(root) }
            }
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun applyToTree(view: View) {
        if (view.isClickable || view.isLongClickable) applyToClickableView(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyToTree(view.getChildAt(index))
        }
    }

    private fun applyToClickableView(view: View) {
        if (styledViews.put(view, true) != null) return

        // A foreground ripple works over custom backgrounds without replacing them.
        // Material widgets that already provide a ripple are left untouched.
        val hasRippleBackground = view.background is RippleDrawable
        if (view.foreground == null && !hasRippleBackground && !view.isInEditMode) {
            val shapeMask = view.background?.constantState
                ?.newDrawable(view.resources)?.mutate()
                ?: ColorDrawable(Color.WHITE)
            view.foreground = RippleDrawable(
                ColorStateList.valueOf(resolveRippleColor(view)),
                null,
                shapeMask
            )
        }

        // Subtle press compression gives touch feedback without changing layout size.
        // Keep any component-specific animator (e.g. Material buttons) intact.
        if (view.stateListAnimator == null && !view.isInEditMode) {
            view.stateListAnimator = createPressAnimator(view)
        }
    }

    private fun resolveRippleColor(view: View): Int {
        val value = TypedValue()
        val resolved = view.context.theme.resolveAttribute(
            android.R.attr.colorControlHighlight,
            value,
            true
        )
        if (resolved) {
            if (value.resourceId != 0) {
                return try {
                    view.context.getColor(value.resourceId)
                } catch (_: Exception) {
                    value.data
                }
            }
            return value.data
        }
        return Color.argb(42, 90, 90, 90)
    }

    private fun createPressAnimator(view: View): StateListAnimator {
        val animator = StateListAnimator()
        val pressed = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.982f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.982f)
            )
            duration = 95L
            interpolator = DecelerateInterpolator()
        }
        val released = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.982f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.982f, 1f)
            )
            duration = 180L
            interpolator = DecelerateInterpolator()
        }
        animator.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        animator.addState(intArrayOf(), released)
        return animator
    }

    companion object {
        fun install(application: Application) {
            application.registerActivityLifecycleCallbacks(GlobalTouchEffects())
        }
    }
}
