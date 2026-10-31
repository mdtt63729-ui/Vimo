package com.atube.app.helpers

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Upgrades YouTube thumbnail URLs to the highest available quality.
 *
 * YouTube serves thumbnails at several fixed resolutions:
 *   maxresdefault  1280×720  (best, not always available)
 *   sddefault       640×480
 *   hqdefault       480×360
 *   mqdefault       320×180  (often returned by the API for feed items)
 *   default         120×90
 *
 * [upgradeThumbnailUrl] rewrites any YouTube thumbnail URL to request
 * `maxresdefault`. Since `maxresdefault` is not guaranteed to exist for every
 * video, callers should pair this with a fallback load (see
 * [ImageHelper.loadImageHighQuality]) that retries with the next-best
 * resolution on failure.
 */
object ThumbnailHelper {

    /**
     * hqdefault comes first on purpose: it exists for every video, while
     * maxresdefault and sddefault only exist for HD uploads. Starting at the top
     * meant most thumbnails cost two failed round trips before the first hit, which
     * is what made the feed feel slow. The bigger sizes are still tried, but only
     * after the one that is guaranteed to answer.
     */
    val QUALITY_FALLBACK_CHAIN = listOf(
        "hqdefault",
        "maxresdefault",
        "sddefault",
        "mqdefault",
        "default",
    )

    private const val YOUTUBE_IMG_HOST = "img.youtube.com"
    private const val YT_IMG_HOST = "ytimg.com"

    fun isYouTubeThumbnail(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host
        return host == YOUTUBE_IMG_HOST || host.endsWith(YT_IMG_HOST)
    }

    fun upgradeThumbnailUrl(url: String?, targetQuality: String = "maxresdefault"): String? {
        if (url.isNullOrBlank()) return url

        val parsed = url.toHttpUrlOrNull() ?: return url

        val host = parsed.host
        val isYouTube = host == YOUTUBE_IMG_HOST ||
            host.endsWith(YT_IMG_HOST) ||
            host.endsWith(".ytimg.com")

        val proxiedHost = parsed.queryParameter("host")
        val isProxiedYouTube = !proxiedHost.isNullOrEmpty() &&
            (proxiedHost == YOUTUBE_IMG_HOST || proxiedHost.endsWith(YT_IMG_HOST))

        if (!isYouTube && !isProxiedYouTube) return url

        val pathSegments = parsed.pathSegments
        if (pathSegments.isEmpty()) return url

        val lastSegment = pathSegments.last()
        val dotIndex = lastSegment.lastIndexOf('.')
        if (dotIndex < 0) return url

        val currentQuality = lastSegment.substring(0, dotIndex)
        if (currentQuality !in QUALITY_FALLBACK_CHAIN) return url

        val rebuilt = pathSegments.toMutableList()
        val extension = lastSegment.substring(dotIndex)
        rebuilt[rebuilt.lastIndex] = targetQuality + extension
        return parsed.newBuilder()
            .encodedPath("/" + rebuilt.joinToString("/"))
            .build()
            .toString()
    }

    fun nextLowerQuality(quality: String): String? {
        val index = QUALITY_FALLBACK_CHAIN.indexOf(quality)
        if (index < 0 || index >= QUALITY_FALLBACK_CHAIN.lastIndex) return null
        return QUALITY_FALLBACK_CHAIN[index + 1]
    }

    fun currentQuality(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        val lastSegment = parsed.pathSegments.lastOrNull() ?: return null
        val dotIndex = lastSegment.lastIndexOf('.')
        if (dotIndex <= 0) return null
        val token = lastSegment.substring(0, dotIndex)
        return token.takeIf { it in QUALITY_FALLBACK_CHAIN }
    }
}
