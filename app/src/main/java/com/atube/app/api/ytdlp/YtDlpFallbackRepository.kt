package com.atube.app.api.ytdlp

import android.util.Log
import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.TrendingCategory
import com.atube.app.api.obj.ContentItem
import com.atube.app.api.obj.SearchResult
import com.atube.app.api.obj.StreamItem
import com.atube.app.api.obj.Streams
import com.atube.app.api.muso.MusoStreamResolver

/**
 * Wraps the chosen [MediaServiceRepository] and gives it one extra chance.
 *
 * The SABR player, NewPipe Extractor and the Piped API are tried first, exactly as
 * before. Only when they produce nothing does the on-device yt-dlp engine step in —
 * for streams, for search results and for the home feed's trending candidates.
 * That keeps the fast path untouched and stops a blocked or unreachable primary
 * extractor from leaving every screen on its "Retry" button.
 */
class YtDlpFallbackRepository(
    private val base: MediaServiceRepository
) : MediaServiceRepository by base {

    override suspend fun getStreams(videoId: String): Streams {
        val primary = runCatching { base.getStreams(videoId) }.getOrNull()
        if (primary != null && primary.hasPlayableStreams()) return primary

        // Second fallback: the on-device InnerTube resolver. If the primary extractor
        // could not produce a stream, playback is served from here instead.
        val viaMuso = runCatching { MusoStreamResolver.resolve(videoId) }.getOrNull()
        if (viaMuso != null && viaMuso.hasPlayableStreams()) {
            Log.i(TAG, "resolved $videoId with the InnerTube fallback")
            return viaMuso
        }

        val viaYtDlp = runCatching { YtDlpStreamResolver.resolve(videoId) }.getOrNull()
        if (viaYtDlp != null && viaYtDlp.hasPlayableStreams()) {
            Log.i(TAG, "resolved $videoId with the on-device yt-dlp engine")
            return viaYtDlp
        }

        return primary
            ?: viaYtDlp
            ?: throw IllegalStateException("Could not load video streams for $videoId")
    }

    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult {
        val primary = runCatching { base.getSearchResults(searchQuery, filter) }.getOrNull()
        if (primary != null && primary.items.isNotEmpty()) return primary

        val viaYtDlp = runCatching { YtDlpStreamResolver.searchItems(searchQuery) }
            .getOrDefault(emptyList())
        if (viaYtDlp.isNotEmpty()) {
            Log.i(TAG, "search for \"$searchQuery\" answered by the on-device yt-dlp engine")
            return SearchResult(items = viaYtDlp.map { it.toContentItem() })
        }
        return primary ?: SearchResult()
    }

    override suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult = runCatching { base.getSearchResultsNextPage(searchQuery, filter, nextPage) }
        .getOrElse { SearchResult() }

    override suspend fun getTrending(
        region: String,
        category: TrendingCategory
    ): List<StreamItem> {
        val primary = runCatching { base.getTrending(region, category) }.getOrNull()
        if (!primary.isNullOrEmpty()) return primary

        val viaYtDlp = runCatching {
            YtDlpStreamResolver.searchItems(trendingQuery(category), limit = 20)
        }.getOrDefault(emptyList())
        if (viaYtDlp.isNotEmpty()) {
            Log.i(TAG, "trending ${category.name} answered by the on-device yt-dlp engine")
            return viaYtDlp
        }
        return primary.orEmpty()
    }

    /** Trending has no yt-dlp equivalent, so each category becomes a search. */
    private fun trendingQuery(category: TrendingCategory): String = when (category) {
        TrendingCategory.GAMING -> "gaming"
        TrendingCategory.TRAILERS -> "movie trailer"
        TrendingCategory.PODCASTS -> "podcast"
        TrendingCategory.MUSIC -> "music video"
        TrendingCategory.LIVE -> "live news"
    }

    private fun StreamItem.toContentItem() = ContentItem(
        url = url.orEmpty(),
        type = StreamItem.TYPE_STREAM,
        thumbnail = thumbnail.orEmpty(),
        title = title,
        uploaderUrl = uploaderUrl,
        uploaderAvatar = uploaderAvatar,
        duration = duration ?: -1,
        views = views ?: -1,
        isShort = isShort,
        uploaderVerified = uploaderVerified,
        uploaderName = uploaderName,
        uploaded = uploaded,
        shortDescription = shortDescription
    )

    private fun Streams.hasPlayableStreams(): Boolean =
        videoStreams.isNotEmpty() || hls != null || dash != null || serverAbrStreamingUrl != null

    private companion object {
        const val TAG = "YtDlpFallback"
    }
}
