package com.atube.app.api.ytdlp

import android.util.Log
import com.atube.app.api.obj.PipedStream
import com.atube.app.api.obj.StreamItem
import com.atube.app.api.obj.Streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Turns what yt-dlp reports into A Tube's own models, so the existing player,
 * search screen and downloader can consume it without knowing where it came from.
 *
 * yt-dlp hands back one entry per format, and sites differ wildly: some offer a
 * single muxed file, most offer video-only and audio-only streams separately. Both
 * shapes map onto [PipedStream], which is what the player already understands.
 */
object YtDlpStreamResolver {

    private const val TAG = "YtDlpStreamResolver"

    /** Resolve playable streams for a video id or a full URL. Null when it cannot. */
    suspend fun resolve(videoId: String): Streams? = withContext(Dispatchers.IO) {
        val url = if (videoId.startsWith("http")) {
            videoId
        } else {
            "https://www.youtube.com/watch?v=$videoId"
        }
        val json = try {
            YtDlpEngine.extractInfo(url)
        } catch (t: Throwable) {
            Log.w(TAG, "yt-dlp could not read $url: ${t.message}")
            return@withContext null
        }
        toStreams(url, json)
    }

    /**
     * Run a yt-dlp search and map the hits onto [StreamItem].
     *
     * This is the fallback for the search screen and for the home feed's trending
     * candidates: when the primary extractor comes back empty, the screen still
     * has something to show instead of a "Retry" button.
     */
    suspend fun searchItems(query: String, limit: Int = 20): List<StreamItem> =
        withContext(Dispatchers.IO) {
            val json = YtDlpEngine.search(query, limit)
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            val out = ArrayList<StreamItem>(items.length())
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                if (id.isBlank()) continue
                out += StreamItem(
                    url = id,
                    type = StreamItem.TYPE_STREAM,
                    title = entry.optString("title").takeIf { it.isNotBlank() },
                    thumbnail = entry.optString("thumbnail").takeIf { it.startsWith("http") },
                    uploaderName = entry.optString("uploader").takeIf { it.isNotBlank() },
                    duration = entry.optLong("duration").takeIf { it > 0 },
                    views = entry.optLong("views").takeIf { it > 0 },
                    isShort = false
                )
            }
            out
        }

    fun toStreams(pageUrl: String, json: JSONObject): Streams? {
        val formats = json.optJSONArray("formats") ?: JSONArray()

        val videoStreams = mutableListOf<PipedStream>()
        val audioStreams = mutableListOf<PipedStream>()

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val directUrl = format.optString("url")
            if (directUrl.isBlank()) continue

            val ext = format.optString("ext").ifBlank { "mp4" }
            val hasVideo = format.optBoolean("has_video")
            val hasAudio = format.optBoolean("has_audio")
            val height = format.optInt("height")
            val fps = format.optInt("fps")
            val size = format.optLong("filesize")
            val bitrate = (format.optDouble("tbr", 0.0) * 1000).roundToInt()
            val formatId = format.optString("format_id")

            when {
                hasVideo -> videoStreams += PipedStream(
                    url = directUrl,
                    format = ext,
                    quality = if (height > 0) "${height}p" else format.optString("resolution")
                        .ifBlank { "video" },
                    mimeType = videoMimeFor(ext),
                    codec = format.optString("vcodec").takeIf { it.isNotBlank() && it != "none" },
                    videoOnly = !hasAudio,
                    bitrate = bitrate.takeIf { it > 0 },
                    width = format.optInt("width").takeIf { it > 0 },
                    height = height.takeIf { it > 0 },
                    fps = fps.takeIf { it > 0 },
                    contentLength = if (size > 0) size else -1,
                    itag = formatId.toIntOrNull()
                )

                hasAudio -> {
                    val abr = format.optDouble("abr", 0.0)
                    audioStreams += PipedStream(
                        url = directUrl,
                        format = ext,
                        quality = if (abr > 0) "${abr.roundToInt()} kbps" else "audio",
                        mimeType = audioMimeFor(ext),
                        codec = format.optString("acodec").takeIf { it.isNotBlank() && it != "none" },
                        videoOnly = false,
                        bitrate = (abr * 1000).roundToInt().takeIf { it > 0 },
                        contentLength = if (size > 0) size else -1,
                        itag = formatId.toIntOrNull()
                    )
                }
            }
        }

        if (videoStreams.isEmpty()) return null

        return Streams(
            title = json.optString("title").ifBlank { "Video" },
            description = json.optString("description"),
            uploader = json.optString("uploader").ifBlank { "Unknown" },
            uploaderUrl = null,
            uploaderAvatar = null,
            thumbnailUrl = json.optString("thumbnail").takeIf { it.startsWith("http") }
                ?: pageUrl,
            category = "",
            uploaderVerified = false,
            duration = json.optLong("duration").coerceAtLeast(0L),
            livestream = json.optBoolean("is_live"),
            audioStreams = audioStreams.sortedByDescending { it.bitrate ?: 0 },
            videoStreams = videoStreams.sortedByDescending { it.height ?: 0 }
        )
    }

    private fun videoMimeFor(ext: String): String = when (ext.lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        else -> "video/mp4"
    }

    private fun audioMimeFor(ext: String): String = when (ext.lowercase()) {
        "webm" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/mp4"
    }
}
