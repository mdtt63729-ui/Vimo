package com.atube.app.api

import android.util.Log
import androidx.annotation.StringRes
import com.atube.app.R
import com.atube.app.api.obj.Channel
import com.atube.app.api.obj.ChannelTabResponse
import com.atube.app.api.obj.CommentsPage
import com.atube.app.api.obj.DeArrowContent
import com.atube.app.api.obj.Playlist
import com.atube.app.api.obj.SearchResult
import com.atube.app.api.obj.SegmentData
import com.atube.app.api.obj.StreamItem
import com.atube.app.api.obj.Streams
import com.atube.app.api.ytdlp.YtDlpEngine
import com.atube.app.api.ytdlp.YtDlpFallbackRepository
import com.atube.app.helpers.PlayerHelper

interface MediaServiceRepository {
    fun getTrendingCategories(): List<TrendingCategory>

    suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem>
    suspend fun getStreams(videoId: String): Streams
    suspend fun getComments(videoId: String): CommentsPage
    suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>? = null
    ): SegmentData

    suspend fun getDeArrowContent(videoId: String): DeArrowContent?
    suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage
    suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult
    suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult

    suspend fun getSuggestions(query: String): List<String>
    suspend fun getChannel(channelId: String): Channel
    suspend fun getChannelTab(data: String, nextPage: String? = null): ChannelTabResponse
    suspend fun getChannelByName(channelName: String): Channel
    suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel
    suspend fun getPlaylist(playlistId: String): Playlist
    suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist

    companion object {
        private const val TAG = "MediaServiceRepository"

        val instance: MediaServiceRepository
            get() {
                val base = when {
                    PlayerHelper.fullLocalMode -> NewPipeMediaServiceRepository()
                    PlayerHelper.localStreamExtraction -> LocalStreamsExtractionPipedMediaServiceRepository()
                    else -> PipedMediaServiceRepository()
                }
                // The on-device yt-dlp engine is a last-resort extractor: it only runs
                // when the primary extractor cannot produce playable streams.
                //
                // This is wrapped in a catch-all on purpose. Every screen resolves its
                // repository through this getter, so if anything about the optional
                // yt-dlp layer is unavailable (no Python runtime in the APK, stripped
                // by R8, engine never initialised) it must degrade to the plain
                // repository instead of failing every request and showing "Retry".
                return try {
                    if (YtDlpEngine.isAvailable()) YtDlpFallbackRepository(base) else base
                } catch (t: Throwable) {
                    Log.w(TAG, "yt-dlp fallback unavailable, using the primary extractor", t)
                    base
                }
            }
    }
}

enum class TrendingCategory(@StringRes val titleRes: Int) {
    GAMING(R.string.gaming),
    TRAILERS(R.string.trailers),
    PODCASTS(R.string.podcasts),
    MUSIC(R.string.music),
    LIVE(R.string.live)
}