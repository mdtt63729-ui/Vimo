package com.atube.app.api.ytdlp

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

/**
 * The on-device yt-dlp engine, reached through Chaquopy.
 *
 * Ported from the Off app. yt-dlp is the catch-all extractor: whatever the SABR
 * player, NewPipe Extractor and the Piped API cannot read, this can. It runs
 * entirely on the phone — no A Tube backend, no third-party extraction API.
 *
 * In A Tube it is a *fallback*: [com.atube.app.api.ytdlp.YtDlpFallbackRepository]
 * only calls it when the primary extractor could not produce playable streams.
 *
 * Every call here blocks, so call them off the main thread.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Volatile private var ffmpegReady = false
    @Volatile private var engineVersion: String? = null

    /** Called once from the activity, before anything else touches the engine. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** True when the engine has a context and can be asked to extract something. */
    fun isAvailable(): Boolean = appContext != null

    /** Returns false when the interpreter could not be started. Never throws. */
    private fun ensureStarted(context: Context): Boolean {
        if (started) return true
        synchronized(this) {
            if (started) return true
            return try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                started = true
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start the Python engine", t)
                false
            }
        }
    }

    private fun bridge(context: Context) = Python.getInstance().getModule("bridge")

    /**
     * Points yt-dlp at a bundled ffmpeg, so separate video and audio streams can be
     * merged. A Tube merges with its own downloader, so this is optional: without
     * it only single-file formats can be muxed by yt-dlp itself.
     */
    fun prepare(context: Context): Boolean {
        if (ffmpegReady) return true
        if (!ensureStarted(context)) return false
        return runCatching {
            val dir = File(context.filesDir, "ffmpeg").apply { mkdirs() }
            bridge(context).callAttr(
                "prepare_ffmpeg",
                context.applicationInfo.nativeLibraryDir,
                dir.absolutePath
            )
            ffmpegReady = true
            true
        }.getOrDefault(false)
    }

    /**
     * True when the app actually ships ffmpeg. Merging separate video and audio
     * streams is impossible without it, so the fetch screen only offers a
     * "merge everything" download when this says yes.
     */
    fun ffmpegAvailable(): Boolean {
        val context = appContext ?: return false
        if (!ensureStarted(context)) return false
        return runCatching {
            bridge(context).callAttr("ffmpeg_available").toBoolean()
        }.getOrDefault(false)
    }

    fun version(): String? {
        engineVersion?.let { return it }
        val context = appContext ?: return null
        if (!ensureStarted(context)) return null
        return runCatching {
            bridge(context).callAttr("version").toString().also { engineVersion = it }
        }.getOrNull()
    }

    /** Reads metadata for a link. Throws with a readable message if it cannot. */
    fun extractInfo(url: String): JSONObject {
        val context = appContext
            ?: throw IllegalStateException("The on-device engine is not ready.")
        if (!ensureStarted(context)) {
            throw IllegalStateException("The on-device engine could not start.")
        }
        val raw = runCatching {
            bridge(context).callAttr("extract_info", url).toString()
        }.getOrElse {
            Log.e(TAG, "extract_info failed for $url", it)
            throw IllegalStateException("The on-device engine could not start.")
        }

        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw IllegalStateException("The on-device engine returned something unexpected.")
        if (!json.optBoolean("ok")) {
            throw IllegalStateException(
                json.optString("error").ifBlank { "That link could not be read." }
            )
        }
        return json
    }

    /**
     * Runs a yt-dlp search and returns the raw JSON. Never throws: an empty list
     * comes back when the engine cannot start or the search fails.
     */
    fun search(query: String, limit: Int = 20): JSONObject {
        val context = appContext ?: return EMPTY_SEARCH
        if (!ensureStarted(context)) return EMPTY_SEARCH
        val raw = runCatching {
            bridge(context).callAttr("search", query, limit).toString()
        }.getOrElse {
            Log.w(TAG, "search failed for $query", it)
            return EMPTY_SEARCH
        }
        return runCatching { JSONObject(raw) }.getOrDefault(EMPTY_SEARCH)
    }

    private val EMPTY_SEARCH = JSONObject("{\"ok\":false,\"items\":[]}")

    /**
     * Blocks until the download finishes. A Tube's own downloader is used for
     * normal downloads; this exists for links only yt-dlp understands.
     */
    /**
     * Blocks until the download finishes. Returns the path of the file yt-dlp wrote,
     * or null when the download failed.
     */
    fun startDownload(url: String, selector: String, outputDir: File, jobId: String): String? {
        val context = appContext ?: return null
        if (!ensureStarted(context)) return null
        runCatching { outputDir.mkdirs() }
        return runCatching {
            val raw = bridge(context)
                .callAttr("start_download", url, selector, outputDir.absolutePath, jobId)
                .toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) null
            else json.optString("path").takeIf { it.isNotBlank() } ?: ""
        }.getOrNull()
    }

    /**
     * Downloads the audio track and converts it to MP3 with the bundled ffmpeg.
     * Blocks until finished, so call it off the main thread.
     */
    fun startAudioDownload(url: String, outputDir: File, jobId: String): Boolean {
        val context = appContext ?: return false
        if (!ensureStarted(context)) return false
        runCatching { outputDir.mkdirs() }
        return runCatching {
            val raw = bridge(context)
                .callAttr("start_audio_download", url, outputDir.absolutePath, jobId)
                .toString()
            JSONObject(raw).optBoolean("ok")
        }.getOrDefault(false)
    }

    /**
     * Re-encodes a file's audio to MP3 with the bundled ffmpeg. Blocks, so call
     * it off the main thread. Returns the raw JSON from the bridge.
     */
    fun convertAudio(inputPath: String, outputPath: String, bitrate: String): JSONObject {
        val context = appContext ?: return JSONObject("{\"ok\":false}")
        if (!ensureStarted(context)) return JSONObject("{\"ok\":false}")
        prepare(context)
        val raw = runCatching {
            bridge(context)
                .callAttr("convert_audio", inputPath, outputPath, bitrate)
                .toString()
        }.getOrElse {
            return JSONObject("{\"ok\":false,\"error\":\"The engine could not start.\"}")
        }
        return runCatching { JSONObject(raw) }
            .getOrDefault(JSONObject("{\"ok\":false}"))
    }

    fun cancel(jobId: String) {
        val context = appContext ?: return
        if (!started) return
        runCatching { bridge(context).callAttr("cancel_job", jobId) }
    }

    fun progress(jobId: String): YtDlpProgress? {
        val context = appContext ?: return null
        if (!started) return null
        val raw = runCatching { bridge(context).callAttr("get_progress", jobId).toString() }
            .getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return YtDlpProgress(
            status = json.optString("status"),
            percent = json.optDouble("percent", 0.0).toFloat(),
            message = json.optString("message"),
            speedBytesPerSecond = json.optLong("speed"),
            etaSeconds = if (json.isNull("eta")) null else json.optInt("eta")
        )
    }

    fun cleanup(jobId: String) {
        val context = appContext ?: return
        if (!started) return
        runCatching { bridge(context).callAttr("cleanup_progress", jobId) }
    }
}

data class YtDlpProgress(
    val status: String,
    val percent: Float,
    val message: String,
    val speedBytesPerSecond: Long,
    val etaSeconds: Int?
)
