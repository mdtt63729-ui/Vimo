package com.atube.app.helpers

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.Window
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.atube.app.constants.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic Video Theme Manager for A Tube.
 *
 * Extracts dominant colors from video thumbnails using Android Palette API
 * and applies them to the UI — status bar, navigation bar, key UI surfaces —
 * creating a premium immersive experience where the app adapts to the content.
 *
 * Features:
 * - Palette-based color extraction from thumbnail URL
 * - LRU color cache (bounded, 32 entries)
 * - Smooth animated color transitions (ValueAnimator)
 * - Light/dark mode aware color generation
 * - Material 3 color scheme generation from a single seed color
 */
object DynamicThemeManager {

    private val colorCache = LruCache<String, IntArray>(32)
    private val handler = Handler(Looper.getMainLooper())

    // Current applied colors
    @Volatile
    private var currentPrimary = 0

    @Volatile
    private var currentPrimaryDark = 0

    @Volatile
    private var currentPrimaryContainer = 0

    data class DynamicColors(
        val primary: Int,
        val primaryDark: Int,
        val primaryContainer: Int,
        val onPrimary: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val secondaryContainer: Int,
        val surface: Int,
        val onSurface: Int
    )

    /**
     * Whether dynamic video theme is enabled in settings.
     */
    fun isEnabled(): Boolean {
        return PreferenceHelper.getBoolean(PreferenceKeys.DYNAMIC_THEME, false)
    }

    /**
     * Extract colors from a thumbnail URL and apply them to the activity.
     * Runs on IO thread, then applies colors on main thread.
     *
     * @param activity The activity to apply colors to
     * @param thumbnailUrl The URL of the video thumbnail
     */
    fun applyFromThumbnail(activity: Activity, thumbnailUrl: String?) {
        if (!isEnabled() || thumbnailUrl.isNullOrEmpty()) return

        // Check cache first
        val cached = colorCache[thumbnailUrl]
        if (cached != null) {
            handler.post { applyColorsToActivity(activity, cached) }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bitmap = loadBitmap(activity, thumbnailUrl) ?: return@launch
                val palette = Palette.from(bitmap).generate()
                val colors = extractColors(palette, ThemeHelper.isDarkMode(activity))
                val colorsArray = colorsToArray(colors)
                colorCache.put(thumbnailUrl, colorsArray)
                handler.post { applyColorsToActivity(activity, colorsArray) }
            } catch (e: Exception) {
                // Silent fail — don't break the user's experience
            }
        }
    }

    /**
     * Extract colors from a thumbnail URL and apply to a specific view's background.
     * Used for Shorts where we want to tint the overlay/gradient.
     */
    fun applyToView(context: Context, thumbnailUrl: String?, callback: (DynamicColors) -> Unit) {
        if (!isEnabled() || thumbnailUrl.isNullOrEmpty()) return

        val cached = colorCache[thumbnailUrl]
        if (cached != null) {
            val colors = colorsFromArray(cached)
            handler.post { callback(colors) }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bitmap = loadBitmap(context, thumbnailUrl) ?: return@launch
                val palette = Palette.from(bitmap).generate()
                val colors = extractColors(palette, ThemeHelper.isDarkMode(context))
                colorCache.put(thumbnailUrl, colorsToArray(colors))
                handler.post { callback(colors) }
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                loader.execute(request).image?.toBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Generate a full Material 3 color set from a Palette.
     * Adapts for light/dark mode.
     */
    private fun extractColors(palette: Palette, isDark: Boolean): DynamicColors {
        val dominant = palette.getDominantColor(Color.BLUE)

        // Get vibrant/muted variants for richness
        val vibrant = palette.getVibrantColor(dominant)
        val muted = palette.getMutedColor(dominant)
        val darkVibrant = palette.getDarkVibrantColor(dominant)
        val lightVibrant = palette.getLightVibrantColor(dominant)

        // Primary color — prefer vibrant, fallback to dominant
        val primary = if (isDark) darken(vibrant, 0.15f) else vibrant

        // Primary dark — darker version for status bar
        val primaryDark = darken(primary, 0.3f)

        // Primary container — tinted version
        val primaryContainer = if (isDark) {
            darken(primary, 0.4f)
        } else {
            lighten(primary, 0.6f)
        }

        // On-primary — white or black depending on luminance
        val onPrimary = if (luminance(primary) > 0.5) Color.BLACK else Color.WHITE

        // On-primary-container
        val onPrimaryContainer = if (isDark) {
            lighten(primary, 0.7f)
        } else {
            darken(primary, 0.7f)
        }

        // Secondary — muted variant
        val secondary = if (isDark) darken(muted, 0.1f) else muted
        val secondaryContainer = if (isDark) {
            darken(secondary, 0.3f)
        } else {
            lighten(secondary, 0.5f)
        }

        // Surface — very subtle tint of the dominant color
        val surface = if (isDark) {
            mixColors(dominant, Color.BLACK, 0.85f)
        } else {
            mixColors(dominant, Color.WHITE, 0.92f)
        }

        val onSurface = if (isDark) Color.WHITE else Color.BLACK

        return DynamicColors(
            primary = primary,
            primaryDark = primaryDark,
            primaryContainer = primaryContainer,
            onPrimary = onPrimary,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            secondaryContainer = secondaryContainer,
            surface = surface,
            onSurface = onSurface
        )
    }

    /**
     * Apply extracted colors to an activity — status bar, nav bar, window background,
     * and Material 3 theme attributes (colorPrimary, colorSurface, etc.) so the entire
     * app theme dynamically adapts to the video thumbnail.
     */
    private fun applyColorsToActivity(activity: Activity, colors: IntArray) {
        try {
            val c = colorsFromArray(colors)

            // Status bar — smooth animated transition
            animateStatusBarColor(activity, c.primaryDark)

            // Navigation bar
            animateNavBarColor(activity, c.primaryDark)

            // Window background — subtle tint
            activity.window.decorView.setBackgroundColor(c.surface)

            // Tint key UI surfaces using MaterialColors so Material 3 components
            // (MaterialButton, MaterialCardView, BottomSheet, etc.) pick up the
            // video's dominant color. This is a lightweight runtime overlay that
            // doesn't require recreating the activity.
            applyMaterialColors(activity, c)

            // Store for ThemeHelper access
            currentPrimary = c.primary
            currentPrimaryDark = c.primaryDark
            currentPrimaryContainer = c.primaryContainer

        } catch (e: Exception) {
            // Silent fail
        }
    }

    /**
     * Apply dynamic colors to Material 3 components via the DynamicColors API.
     * This tints all Material 3 surfaces (app bar, buttons, FABs, sheets) with
     * the extracted thumbnail colors.
     */
    private fun applyMaterialColors(activity: Activity, c: DynamicColors) {
        // Tint the root view's background so Material 3 components
        // (MaterialButton, MaterialCardView, BottomSheet, etc.) pick up the
        // video's dominant color via backgroundTintList.
        activity.window.decorView.backgroundTintList =
            android.content.res.ColorStateList.valueOf(c.surface)

        // Note: statusBarColor and navigationBarColor are already set
        // via animateStatusBarColor() and animateNavBarColor() above
        // in applyColorsToActivity(), so we don't duplicate that here.
    }

    /**
     * Smoothly animate the status bar color.
     */
    private fun animateStatusBarColor(activity: Activity, targetColor: Int) {
        val window: Window = activity.window
        val currentColor = window.statusBarColor

        val animator = android.animation.ValueAnimator.ofArgb(currentColor, targetColor)
        animator.duration = 400
        animator.addUpdateListener { animator ->
            window.statusBarColor = animator.animatedValue as Int
        }
        animator.start()
    }

    /**
     * Smoothly animate the navigation bar color.
     */
    private fun animateNavBarColor(activity: Activity, targetColor: Int) {
        val window: Window = activity.window
        val currentColor = window.navigationBarColor

        val animator = android.animation.ValueAnimator.ofArgb(currentColor, targetColor)
        animator.duration = 400
        animator.addUpdateListener { animator ->
            window.navigationBarColor = animator.animatedValue as Int
        }
        animator.start()
    }

    /**
     * Reset the activity colors back to the default theme.
     * Called when the player is destroyed or the app is closed.
     * Resets status bar, nav bar, window background, and root view tint
     * so the app returns to its default Material 3 theme.
     */
    fun resetTheme(activity: Activity) {
        if (!isEnabled()) return

        // Reset to transparent status/nav bar (default Material 3)
        handler.post {
            animateStatusBarColor(activity, Color.TRANSPARENT)
            animateNavBarColor(activity, Color.TRANSPARENT)

            // Reset window background and root view tint
            activity.window.decorView.setBackgroundColor(Color.TRANSPARENT)
            activity.window.decorView.backgroundTintList = null
        }

        currentPrimary = 0
        currentPrimaryDark = 0
        currentPrimaryContainer = 0
    }

    // === Color utility functions ===

    /**
     * Get the current dynamic primary color (or 0 if not set).
     * Used by ThemeHelper and UI components to check if a dynamic theme is active.
     */
    fun getCurrentPrimary(): Int = currentPrimary

    fun getCurrentPrimaryDark(): Int = currentPrimaryDark

    fun getCurrentPrimaryContainer(): Int = currentPrimaryContainer

    fun hasActiveTheme(): Boolean = currentPrimary != 0

    private fun lighten(color: Int, factor: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.rgb(
            min(255, (r + (255 - r) * factor).toInt()),
            min(255, (g + (255 - g) * factor).toInt()),
            min(255, (b + (255 - b) * factor).toInt())
        )
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return Color.rgb(
            max(0, (r * (1 - factor)).toInt()),
            max(0, (g * (1 - factor)).toInt()),
            max(0, (b * (1 - factor)).toInt())
        )
    }

    private fun mixColors(color1: Int, color2: Int, ratio: Float): Int {
        val r = (Color.red(color1) * ratio + Color.red(color2) * (1 - ratio)).toInt()
        val g = (Color.green(color1) * ratio + Color.green(color2) * (1 - ratio)).toInt()
        val b = (Color.blue(color1) * ratio + Color.blue(color2) * (1 - ratio)).toInt()
        return Color.rgb(r, g, b)
    }

    private fun luminance(color: Int): Double {
        return ColorUtils.calculateLuminance(color)
    }

    // === Array conversion for LruCache ===

    private fun colorsToArray(c: DynamicColors): IntArray {
        return intArrayOf(
            c.primary, c.primaryDark, c.primaryContainer,
            c.onPrimary, c.onPrimaryContainer, c.secondary,
            c.secondaryContainer, c.surface, c.onSurface
        )
    }

    private fun colorsFromArray(arr: IntArray): DynamicColors {
        if (arr.size < 9) return DynamicColors(
            Color.BLUE, darken(Color.BLUE, 0.3f), lighten(Color.BLUE, 0.6f),
            Color.WHITE, darken(Color.BLUE, 0.7f), Color.GRAY,
            lighten(Color.GRAY, 0.5f), Color.WHITE, Color.BLACK
        )
        return DynamicColors(
            primary = arr[0],
            primaryDark = arr[1],
            primaryContainer = arr[2],
            onPrimary = arr[3],
            onPrimaryContainer = arr[4],
            secondary = arr[5],
            secondaryContainer = arr[6],
            surface = arr[7],
            onSurface = arr[8]
        )
    }
}
