package com.atube.app.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.storage.StorageManager
import android.widget.ImageView
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.load
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import coil3.toBitmap
import com.atube.app.BuildConfig
import com.atube.app.extensions.toAndroidUri
import com.atube.app.util.DataSaverMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.nio.file.Path

object ImageHelper {
    private lateinit var imageLoader: ImageLoader

    private val Context.coilFile get() = cacheDir.resolve("coil")
    private const val HTTP_SCHEME = "http"

    /**
     * Initialize the image loader
     */
    fun initializeImageLoader(context: Context) {
        val httpClient = OkHttpClient().newBuilder()

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            httpClient.addInterceptor(loggingInterceptor)
        }

        imageLoader = ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(httpClient.build())
                )
            }
            .apply {
                diskCachePolicy(CachePolicy.ENABLED)
                memoryCachePolicy(CachePolicy.ENABLED)

                val storageManager = context.getSystemService<StorageManager>()!!
                val availableCache = storageManager.getCacheQuotaBytes(
                    storageManager.getUuidForPath(context.coilFile)
                )
                // Allow generous disk room for full-resolution thumbnails.
                val diskLimit = availableCache.coerceAtLeast(256L * 1024 * 1024)
                val diskCache = DiskCache.Builder()
                    .directory(context.coilFile)
                    .maxSizeBytes(diskLimit)
                    .build()
                diskCache(diskCache)
            }
            .build()
    }

    /**
     * Checks if the corresponding image for the given key (e.g. a url) is cached.
     */
    private fun isCached(key: String): Boolean {
        val cacheSnapshot = imageLoader.diskCache?.openSnapshot(key)
        val isCacheHit = cacheSnapshot?.data?.toFile()?.exists()
        cacheSnapshot?.close()

        return isCacheHit ?: false
    }

    /**
     * load an image from a url into an imageView
     */
    fun loadImage(url: String?, target: ImageView, whiteBackground: Boolean = false) {
        if (url.isNullOrEmpty()) return

        // clear image to avoid loading issues at fast scrolling
        target.setImageBitmap(null)

        val urlToLoad = ProxyHelper.rewriteUrlUsingProxyPreference(url)

        // only load online images if the data saver mode is disabled
        if (DataSaverMode.isEnabled(target.context)) {
            if (urlToLoad.startsWith(HTTP_SCHEME) && !isCached(urlToLoad)) return
        }

        target.load(urlToLoad) {
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    // set the background to white for transparent images
                    if (whiteBackground) target.setBackgroundColor(Color.WHITE)
                }
            )
        }
    }

    /**
     * High-quality variant of [loadImage] for video thumbnails.
     *
     * - Upgrades YouTube thumbnail URLs to `maxresdefault` (1280×720)
     * - Uses `Size.ORIGINAL` so Coil does NOT downsample to the
     *   ImageView's on-screen size — keeps full-resolution source
     * - Fallback chain: if maxresdefault 404s, retries with sddefault →
     *   hqdefault → mqdefault → default
     */
    fun loadImageHighQuality(
        url: String?,
        target: ImageView,
        whiteBackground: Boolean = false,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        if (url.isNullOrEmpty()) {
            onResult?.invoke(false)
            return
        }

        target.setImageBitmap(null)

        val candidateUrls = buildFallbackChain(url)
        if (candidateUrls.isEmpty()) {
            onResult?.invoke(false)
            return
        }

        // Data saver: only fetch if a candidate is already cached.
        if (DataSaverMode.isEnabled(target.context)) {
            val cached = candidateUrls.firstOrNull { isCached(it) }
            if (cached == null) {
                onResult?.invoke(false)
                return
            }
            loadSingle(cached, target, whiteBackground, highQuality = true, onResult = onResult)
            return
        }

        loadWithFallback(candidateUrls, target, whiteBackground, index = 0, onResult = onResult)
    }

    private fun buildFallbackChain(url: String): List<String> {
        if (!ThumbnailHelper.isYouTubeThumbnail(url)) {
            return listOf(ProxyHelper.rewriteUrlUsingProxyPreference(url))
        }

        val result = mutableListOf<String>()
        var quality: String? = "maxresdefault"
        while (quality != null) {
            val upgraded = ThumbnailHelper.upgradeThumbnailUrl(url, quality)
            if (upgraded != null) {
                val proxied = ProxyHelper.rewriteUrlUsingProxyPreference(upgraded)
                if (proxied !in result) result.add(proxied)
            }
            quality = ThumbnailHelper.nextLowerQuality(quality)
        }
        val originalProxied = ProxyHelper.rewriteUrlUsingProxyPreference(url)
        if (originalProxied !in result) result.add(originalProxied)
        return result
    }

    private fun loadWithFallback(
        urls: List<String>,
        target: ImageView,
        whiteBackground: Boolean,
        index: Int,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        if (index >= urls.size) {
            onResult?.invoke(false)
            return
        }
        loadSingle(
            urls[index],
            target,
            whiteBackground,
            highQuality = true,
            onError = { loadWithFallback(urls, target, whiteBackground, index + 1, onResult) },
            onResult = onResult
        )
    }

    private fun loadSingle(
        url: String,
        target: ImageView,
        whiteBackground: Boolean,
        highQuality: Boolean,
        onError: (() -> Unit)? = null,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        target.load(url) {
            // A slightly longer fade than Coil's default, so a thumbnail slides in
            // over the skeleton instead of popping.
            crossfade(250)
            if (highQuality) {
                // Load at original resolution — prevents Coil from
                // downsampling to the ImageView's on-screen size
                size(Size.ORIGINAL)
            }
            listener(
                onSuccess = { _, _ ->
                    if (whiteBackground) target.setBackgroundColor(Color.WHITE)
                    onResult?.invoke(true)
                },
                onError = { _, _ -> onError?.invoke() }
            )
        }
    }

    suspend fun downloadImage(context: Context, url: String, path: Path) {
        val upgradedUrl = ThumbnailHelper.upgradeThumbnailUrl(url) ?: url
        val bitmap = getImage(context, upgradedUrl) ?: getImage(context, url) ?: return
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(path.toAndroidUri())?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    suspend fun getImage(context: Context, url: String?): Bitmap? {
        return getImage(context, url?.toUri())
    }

    suspend fun getImage(context: Context, url: Uri?): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()

        return imageLoader.execute(request).image?.toBitmap()
    }

    fun insertText(bitmap: Bitmap, text: String, posX: Float, posY: Float, fontSize: Float) {
        val canvas = Canvas(bitmap)

        canvas.drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
        canvas.drawText(text, bitmap.width * posX, bitmap.height * posY, Paint().apply {
            style = Paint.Style.FILL
            textSize = fontSize
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        })
    }
}
