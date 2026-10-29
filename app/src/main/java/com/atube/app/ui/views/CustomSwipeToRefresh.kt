package com.atube.app.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_MOVE
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

class CustomSwipeToRefresh(context: Context?, attrs: AttributeSet?) :
    SwipeRefreshLayout(context!!, attrs) {
    private val mTouchSlop: Int = ViewConfiguration.get(this.context).scaledTouchSlop
    private var mPrevX = 0f

    init {
        // The stock circular spinner is hidden: the screens that use this layout
        // draw the Material 3 loading indicator in the same spot instead, so the
        // gesture still works but the old circle never shows.
        setColorSchemeColors(Color.TRANSPARENT)
        setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
    }

    @SuppressLint("Recycle")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> mPrevX = MotionEvent.obtain(event).x
            ACTION_MOVE -> {
                val eventX = event.x
                val xDiff = abs(eventX - mPrevX)
                if (xDiff > mTouchSlop) {
                    return false
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }
}
