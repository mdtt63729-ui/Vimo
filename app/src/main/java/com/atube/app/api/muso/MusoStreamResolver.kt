package com.atube.app.api.muso

import android.content.Context
import android.util.Log
import com.atube.app.api.obj.PipedStream
import com.atube.app.api.obj.Streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiAudioQuality
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiMediaType
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiNetworkConfiguration
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiResolutionPriority
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiResolvedStream
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiResolver
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiStreamRequest
import okhttp3.Dns
import java.util.Locale
import java.util.TimeZone

/**
 * The second fallback in the playback chain, after the primary extractor (SABR /
 * NewPipe / Piped) and before the on-device yt-dlp engine.
 *
 * This wraps the InnerTube resolver from the Muso streaming sources: it asks
 * YouTube's InnerTube endpoint for a player response, deobfuscates the stream URL
 * inside a QuickJS runtime, and returns playable video and audio URLs.
 *
 * Everything here is best effort. Any failure returns null so the caller can move on
 * to the next fallback instead of surfacing an error.
 */
object MusoStreamResolver {

    private const val TAG = "MusoStreamResolver"

    @Volatile private var appContext: Context? = null
    @Volatile private var resolver: YoutubeiResolver? = null

    /** Called once from the activity, before anything asks for a stream. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isAvailable(): Boolean = appContext != null

    private fun resolver(context: Context): YoutubeiResolver =
        resolver ?: synchronized(this) {
            resolver ?: YoutubeiResolver(
                context = context.applicationContext,
                diagnostics = { message -> Log.d(TAG, message) },
                networkConfigurationProvider = {
                    YoutubeiNetworkConfiguration(
                        proxy = null,
                        proxyUsername = null,
                        proxyPassword = null,
                        dns = Dns.SYSTEM,
                        interceptors = emptyList()
                    )
                }
            ).also { resolver = it }
        }

    /** Resolve playable streams for a video id. Null when this fallback cannot. */
    suspend fun resolve(videoId: String): Streams? = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext null
        val engine = resolver(context)

        // Boots QuickJS and warms the InnerTube session. Cheap after the first call.
        runCatching { engine.preWarm() }

        try {
            val video = engine.resolve(
                request = request(videoId, YoutubeiMediaType.VIDEO, maxVideoHeight = 1080),
                priority = YoutubeiResolutionPriority.FOREGROUND
            )
            val audio = if (video.isMuxed) {
                null
            } else {
                engine.resolve(
                    request = request(videoId, YoutubeiMediaType.AUDIO, maxVideoHeight = null),
                    priority = YoutubeiResolutionPriority.FOREGROUND
                )
            }
            toStreams(videoId, video, audio)
        } catch (t: Throwable) {
            Log.w(TAG, "could not resolve $videoId: ${t.message}")
            null
        }
    }

    private fun request(
        videoId: String,
        mediaType: YoutubeiMediaType,
        maxVideoHeight: Int?
    ) = YoutubeiStreamRequest(
        mediaId = videoId,
        quality = YoutubeiAudioQuality.AUTO,
        networkMetered = false,
        authFingerprint = "anonymous",
        pinnedItag = null,
        mediaType = mediaType,
        maxVideoHeight = maxVideoHeight,
        language = Locale.getDefault().language,
        location = Locale.getDefault().country.ifBlank { "US" },
        timezone = TimeZone.getDefault().id
    )

    private fun toStreams(
        videoId: String,
        video: YoutubeiResolvedStream,
        audio: YoutubeiResolvedStream?
    ): Streams? {
        if (video.url.isBlank()) return null

        val videoStreams = mutableListOf(video.toPipedStream(videoOnly = !video.isMuxed))
        if (audio != null && audio.url.isNotBlank()) {
            videoStreams += audio.toPipedStream(videoOnly = false, isAudio = true)
        }

        return Streams(
            title = video.title?.takeIf { it.isNotBlank() } ?: "Video",
            description = "",
            uploader = "",
            uploaderUrl = null,
            uploaderAvatar = null,
            thumbnailUrl = video.thumbnailUrl?.takeIf { it.startsWith("http") }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            category = "",
            uploaderVerified = false,
            duration = (video.durationSeconds ?: 0).toLong(),
            audioStreams = videoStreams.filter { it.videoOnly == false },
            videoStreams = videoStreams.filter { it.videoOnly == true }
        )
    }

    private fun YoutubeiResolvedStream.toPipedStream(
        videoOnly: Boolean,
        isAudio: Boolean = false
    ) = PipedStream(
        url = url,
        format = extensionOf(mimeType),
        quality = when {
            isAudio -> "${(bitrate / 1000).coerceAtLeast(1)} kbps"
            height != null && height > 0 -> "${height}p"
            else -> "video"
        },
        mimeType = mimeType,
        codec = codecs.takeIf { it.isNotBlank() },
        videoOnly = videoOnly,
        bitrate = bitrate.takeIf { it > 0 },
        width = width?.takeIf { it > 0 },
        height = height?.takeIf { it > 0 },
        fps = fps?.takeIf { it > 0 },
        contentLength = if (contentLength > 0) contentLength else -1,
        itag = formatId.takeIf { it > 0 }
    )

    private fun extensionOf(mimeType: String): String = when {
        mimeType.contains("webm", ignoreCase = true) -> "webm"
        mimeType.contains("3gpp", ignoreCase = true) -> "3gp"
        mimeType.startsWith("audio/", ignoreCase = true) -> "m4a"
        else -> "mp4"
    }
}
