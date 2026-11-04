package com.atube.app.ui.fragments

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.atube.app.extensions.toID
import com.atube.app.helpers.ImageHelper
import android.view.Gravity
import android.graphics.Color
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.atube.app.R
import com.atube.app.api.ytdlp.YtDlpEngine
import com.atube.app.api.ytdlp.YtDlpProgress
import com.atube.app.constants.IntentData
import com.atube.app.constants.PreferenceKeys
import com.atube.app.databinding.FragmentDownloadContentBinding
import com.atube.app.databinding.ItemYtdlpDownloadBinding
import com.atube.app.db.obj.DownloadItem
import com.atube.app.enums.FileType
import com.atube.app.databinding.FragmentDownloadsBinding
import com.atube.app.db.DatabaseHolder.Database
import com.atube.app.db.obj.Download
import com.atube.app.db.obj.DownloadPlaylistWithDownload
import com.atube.app.db.obj.DownloadWithItems
import com.atube.app.db.obj.filterByTab
import com.atube.app.extensions.ceilHalf
import com.atube.app.extensions.dpToPx
import com.atube.app.extensions.formatAsFileSize
import com.atube.app.extensions.serializable
import com.atube.app.extensions.setOnDismissListener
import com.atube.app.helpers.DownloadHelper
import com.atube.app.helpers.NavigationHelper
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.obj.DownloadStatus
import com.atube.app.parcelable.PlayerData
import com.atube.app.receivers.DownloadReceiver
import com.atube.app.services.DownloadService
import com.atube.app.ui.adapters.DownloadPlaylistAdapter
import com.atube.app.ui.adapters.YtDlpFetchAdapter
import com.atube.app.ui.adapters.YtDlpResult
import com.atube.app.ui.adapters.DownloadsAdapter
import com.atube.app.ui.base.DynamicLayoutManagerFragment
import com.atube.app.ui.extensions.setOnBackPressed
import com.atube.app.ui.models.CommonPlayerViewModel
import com.atube.app.ui.models.DownloadsViewModel
import com.atube.app.ui.sheets.BaseBottomSheet
import com.atube.app.ui.viewholders.DownloadsViewHolder
import com.atube.app.ui.views.ShimmerHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.path.fileSize
import java.io.File
import java.nio.file.Path
import kotlin.math.roundToInt

enum class DownloadTab {
    VIDEO,
    AUDIO,
    PLAYLIST
}

enum class DownloadSortingOrder(@StringRes val stringId: Int) {
    OLDEST(R.string.least_recent),
    NEWEST(R.string.most_recent),
    ALPHABETIC(R.string.alphabetic),
    DURATION(R.string.duration),
    CHANNEL(R.string.sort_channel),
    SIZE(R.string.sort_size)
}

/**
 * The Downloader tab.
 *
 * A link box, a Fetch button and the result. Everything the old screen held - the
 * three tabs of already-downloaded files - is gone: this screen exists to take a
 * link, hand it to the on-device yt-dlp engine and show what came back.
 *
 * A link is read with extract_info(), which returns the video plus every format
 * yt-dlp could see; anything that is not a link is treated as a search and runs
 * through yt-dlp's ytsearch pseudo-extractor. Tapping a search hit loads that
 * video's formats, tapping a format copies its direct stream URL.
 */
/** Marks a download that came from the yt-dlp fetch screen, not the app downloader. */
const val YTDLP_ID_PREFIX = "ytdlp_"

class DownloadsFragment : Fragment(R.layout.fragment_downloads) {
    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: YtDlpFetchAdapter
    private var fetchJob: Job? = null
    private var skeletonJob: Job? = null

    private var fetchedVideo: YtDlpResult.Video? = null
    private var videoFormats: List<YtDlpResult.Format> = emptyList()
    private var audioFormats: List<YtDlpResult.Format> = emptyList()
    private var selectedFormat: YtDlpResult.Format? = null
    private var audioMode = false

    private val downloadRows = mutableMapOf<String, ItemYtdlpDownloadBinding>()
    /** jobId -> videoId of the row registered in the download database. */
    private val downloadedIds = mutableMapOf<String, String>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private var currentTitle = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // Keep the final action clear of gesture/navigation bars on edge-to-edge devices.
        val scroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.downloadScroll)
        val initialBottom = scroll.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { target, insets ->
            val bottomInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight,
                initialBottom + bottomInset)
            insets
        }

        // The Vimo header behaves the same here as on Home: away on scroll down, back on
        // scroll up. Each step is the difference since the last one, which is what the
        // handler expects.
        // The header no longer hides on scroll here either.
        androidx.core.view.ViewCompat.requestApplyInsets(scroll)

        adapter = YtDlpFetchAdapter(
            onVideoClick = { video -> if (video.url.isNotBlank()) fetchLink(video.url) },
            onFormatDownload = { format -> startDownload(format) }
        )

        binding.fetchButton.setOnClickListener { startFetch() }
        binding.linkInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startFetch()
                true
            } else {
                false
            }
        }
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }

        binding.videoMode.setOnClickListener {
            if (audioMode) { audioMode = false; renderMode() }
        }
        binding.audioMode.setOnClickListener {
            if (!audioMode) { audioMode = true; renderMode() }
        }
        binding.downloadButton.setOnClickListener {
            selectedFormat?.let { startDownload(it) }
        }

        shimmerSkeleton()
        binding.fetchEmpty.isGone = true
        binding.fetchEmptyMessage.isGone = true
        loadPreviousDownloads()

        // Starting Chaquopy takes a few seconds the first time. Do it now, off the
        // main thread, so the first Fetch does not sit waiting for it.
        YtDlpEngine.init(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { YtDlpEngine.version() }
        }
    }

    fun bindDownloadService() = Unit

    // ---- fetching -----------------------------------------------------------

    private fun startFetch() {
        val input = binding.linkInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            Toast.makeText(requireContext(), R.string.fetch_need_input, Toast.LENGTH_SHORT).show()
            return
        }

        if (input.startsWith("http://") || input.startsWith("https://")) {
            fetchLink(input)
        } else {
            runSearch(input)
        }
    }

    private fun fetchLink(url: String) {
        runFetch {
            // Direct image URLs are not necessarily supported by yt-dlp extractors.
            // Detect them first and expose the original file as a download option.
            directImageResult(url)?.let { return@runFetch it }
            try {
                val json = YtDlpEngine.extractInfo(url)
                if (!json.optBoolean("ok")) {
                    throw IllegalStateException(
                        json.optString("error").ifBlank { getString(R.string.fetch_failed) }
                    )
                }
                itemsFromInfo(json)
            } catch (blocked: Throwable) {
                // YouTube answers yt-dlp with "Sign in to confirm you're not a bot"
                // from a phone. The app already ships an InnerTube resolver for
                // playback, so use the same one here instead of failing.
                val videoId = youtubeIdOf(url) ?: throw blocked
                val streams = com.atube.app.api.muso.MusoStreamResolver.resolve(videoId)
                    ?: throw blocked
                itemsFromInnerTube(videoId, streams, url)
            }
        }
    }

    /** Read direct image URL metadata without re-encoding or reducing its quality. */
    private fun directImageResult(url: String): List<YtDlpResult>? {
        val connection = runCatching {
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-65535")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
                connect()
            }
        }.getOrNull() ?: return null
        return try {
            val mime = connection.contentType?.substringBefore(';')?.lowercase().orEmpty()
            val path = runCatching { java.net.URL(connection.url.toString()).path }.getOrDefault("")
            val ext = path.substringAfterLast('.', "").lowercase()
            val isImage = mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif", "tif", "tiff")
            if (!isImage) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
            val width = bounds.outWidth.takeIf { it > 0 } ?: 4
            val height = bounds.outHeight.takeIf { it > 0 } ?: 3
            val fileName = path.substringAfterLast('/').substringBefore('?').ifBlank { "image.$ext" }
            currentTitle = fileName
            listOf(
                YtDlpResult.Video(
                    id = "image_${url.hashCode().toUInt().toString(16)}",
                    title = fileName,
                    uploader = "Original image · ${width} × ${height}",
                    duration = 0L,
                    thumbnail = connection.url.toString(),
                    views = 0L,
                    url = connection.url.toString(),
                    aspect = width.toFloat() / height.toFloat(),
                    isImage = true
                ),
                YtDlpResult.Format(
                    label = "Download original · ${width} × ${height}",
                    detail = "Original image quality · No recompression",
                    directUrl = connection.url.toString(),
                    formatId = "direct",
                    pageUrl = connection.url.toString(),
                    isAudio = false
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    /** The video id of a YouTube link, or null when it is not one. */
    private fun youtubeIdOf(url: String): String? {
        if (!url.contains("youtu")) return null
        return when {
            url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore('&')
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore('?')
            url.contains("/shorts/") -> url.substringAfter("/shorts/").substringBefore('?')
            else -> null
        }?.takeIf { it.length in 6..20 }
    }

    /** Turns InnerTube's resolved streams into the same rows the fetch screen uses. */
    private fun itemsFromInnerTube(
        videoId: String,
        streams: com.atube.app.api.obj.Streams,
        pageUrl: String
    ): List<YtDlpResult> {
        currentTitle = streams.title
        val out = mutableListOf<YtDlpResult>()

        val firstVideo = streams.videoStreams.firstOrNull {
            (it.width ?: 0) > 0 && (it.height ?: 0) > 0
        }
        val aspect = firstVideo?.let {
            (it.width ?: 16).toFloat() / (it.height ?: 9).toFloat()
        } ?: (16f / 9f)

        out += YtDlpResult.Video(
            id = videoId,
            title = streams.title,
            uploader = streams.uploader,
            duration = streams.duration,
            thumbnail = streams.thumbnailUrl,
            views = 0,
            url = pageUrl,
            aspect = aspect
        )

        (streams.videoStreams + streams.audioStreams).forEach { stream ->
            val direct = stream.url?.takeIf { it.startsWith("http") } ?: return@forEach
            val isAudio = stream.videoOnly == false && stream.quality == null
            out += YtDlpResult.Format(
                label = stream.quality ?: if (isAudio) "Audio" else "Video",
                detail = listOfNotNull(
                    stream.format?.takeIf { it.isNotBlank() },
                    if (isAudio) "Audio only" else "Video + audio"
                ).joinToString(" · "),
                directUrl = direct,
                formatId = "direct",
                pageUrl = pageUrl,
                isAudio = isAudio
            )
        }
        return out
    }

    private fun runSearch(query: String) {
        runFetch {
            val json = YtDlpEngine.search(query, 20)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(
                    json.optString("error").ifBlank { getString(R.string.fetch_failed) }
                )
            }
            itemsFromSearch(json)
        }
    }

    private fun runFetch(block: suspend () -> List<YtDlpResult>) {
        fetchJob?.cancel()
        skeletonJob?.cancel()

        // Show the Home feed's shimmer skeleton immediately when Analyse is tapped.
        // The same ShimmerHelper is used by the Home cards, so the loading treatment
        // is identical and the user always gets clear feedback for the request.
        showLoading()
        setFetching(true)

        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { block() } }
            if (_binding == null) return@launch

            skeletonJob?.cancel()
            setFetching(false)

            outcome.onSuccess { items ->
                if (items.isEmpty()) {
                    showEmpty(getString(R.string.fetch_no_results))
                } else {
                    showPreview(items)
                }
            }.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.fetch_failed)
                showEmpty(message)
                // the empty state is small, so the reason is also shown as a bar
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** Keeps the button honest while an attempt is running. */
    private fun setFetching(running: Boolean) {
        binding.fetchButton.isEnabled = !running
        binding.fetchButton.text = getString(
            if (running) R.string.fetch_working else R.string.dl_fetch
        )
    }

    private fun itemsFromInfo(json: JSONObject): List<YtDlpResult> {
        val pageUrl = json.optString("webpage_url")
        val title = json.optString("title").ifBlank { "Untitled" }
        currentTitle = title

        val formats = json.optJSONArray("formats") ?: JSONArray()

        // the real shape of the video, taken from the first stream that reports it
        var aspect = 16f / 9f
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val w = f.optInt("width")
            val h = f.optInt("height")
            if (w > 0 && h > 0) {
                aspect = w.toFloat() / h.toFloat()
                break
            }
        }

        val out = mutableListOf<YtDlpResult>()
        out += YtDlpResult.Video(
            id = json.optString("id"),
            title = title,
            uploader = json.optString("uploader"),
            duration = json.optLong("duration"),
            thumbnail = json.optString("thumbnail"),
            views = 0L,
            url = pageUrl,
            aspect = aspect
        )

        // Prepare the app-bundled FFmpeg before checking capabilities. Merely
        // asking ffmpegAvailable() does not configure yt-dlp's ffmpeg_location.
        // Adaptive YouTube formats (e.g. 720p/1080p) need this to mux video+audio.
        YtDlpEngine.prepare(requireContext())
        val canMerge = YtDlpEngine.ffmpegAvailable()
        val isDirectMedia = json.optString("extractor") == "pinterest"
        if (!isDirectMedia) out += YtDlpResult.Format(
            label = getString(
                if (canMerge) R.string.fetch_best_quality else R.string.fetch_best_single
            ),
            detail = getString(
                if (canMerge) R.string.fetch_best_quality_detail else R.string.fetch_best_single_detail
            ),
            directUrl = "",
            formatId = if (canMerge) "bv*+ba/b" else "b",
            pageUrl = pageUrl,
            isAudio = false
        )

        // MP3 needs ffmpeg's encoder, so it is only offered when it is bundled
        if (canMerge && !isDirectMedia) {
            out += YtDlpResult.Format(
                label = getString(R.string.fetch_audio_mp3),
                detail = getString(R.string.fetch_audio_mp3_detail),
                directUrl = "",
                formatId = "mp3",
                pageUrl = pageUrl,
                isAudio = true,
                convertToMp3 = true
            )
        }

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val direct = format.optString("url")
            val formatId = format.optString("format_id")
            if (formatId.isBlank()) continue

            val ext = format.optString("ext").ifBlank { "file" }
            val resolution = format.optString("resolution").ifBlank { "unknown" }
            val hasVideo = format.optBoolean("has_video")
            val hasAudio = format.optBoolean("has_audio")

            val height = format.optInt("height")
            val shortLabel = when {
                hasVideo && height > 0 -> "${height}p"
                hasVideo -> resolution
                else -> "Audio"
            }
            val label = "$shortLabel · $ext"
            val kind = when {
                hasVideo && hasAudio -> "Video + audio"
                hasVideo -> "Video only"
                else -> "Audio only"
            }
            val size = YtDlpFetchAdapter.formatSize(format.optLong("filesize"))
            val detail = listOf(kind, size).filter { it.isNotBlank() }.joinToString(" · ")

            // A video-only stream carries no sound. Pairing it with the site's best
            // audio is what the video tab promises: 1080p plus the best audio there
            // is. yt-dlp merges the two with the bundled ffmpeg, so the file that
            // lands on disk is a normal video with sound.
            // A video choice always means picture plus sound, the way the other app
            // does it: a video-only stream is paired with the best audio and the two
            // are merged, so the file that lands is a normal video with sound.
            val selector = if (hasVideo && !hasAudio) "$formatId+bestaudio/best" else formatId

            // Every selectable video quality must result in a playable file with
            // sound. Adaptive video-only streams require FFmpeg to mux the selected
            // video stream with the best compatible audio stream. If FFmpeg is not
            // bundled, hide those choices rather than offer a guaranteed failure.
            if (hasVideo && !hasAudio && !canMerge) continue
            // Do not restrict video qualities to MP4 source formats. yt-dlp/FFmpeg
            // can mux compatible WebM/AV1/VP9 source streams into the requested MP4
            // container when supported by the installed FFmpeg build.

            out += YtDlpResult.Format(
                label = label,
                detail = detail,
                directUrl = direct,
                formatId = selector,
                pageUrl = pageUrl,
                isAudio = !hasVideo
            )
        }
        return out
    }

    private fun itemsFromSearch(json: JSONObject): List<YtDlpResult> {
        val items = json.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<YtDlpResult>()
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            out += YtDlpResult.Video(
                id = id,
                title = entry.optString("title").ifBlank { "Untitled" },
                uploader = entry.optString("uploader"),
                duration = entry.optLong("duration"),
                thumbnail = entry.optString("thumbnail"),
                views = entry.optLong("views"),
                url = if (id.isNotBlank()) "https://www.youtube.com/watch?v=$id" else ""
            )
        }
        return out
    }

    // ---- downloading --------------------------------------------------------

    /**
     * Downloads through the same on-device yt-dlp engine that read the link, into
     * the same private folders A Tube's own downloader uses, so a file fetched
     * here lands next to everything else at its original quality.
     */
    private fun startDownload(format: YtDlpResult.Format) {
        if (format.pageUrl.isBlank()) return

        val jobId = "vimo_" + System.currentTimeMillis()
        // everything fetched from a link lands in Vimo/downloader
        val directory = DownloadHelper.getDownloadDir(
            requireContext(), DownloadHelper.DOWNLOADER_DIR
        )

        val row = ItemYtdlpDownloadBinding.inflate(
            LayoutInflater.from(requireContext()), binding.downloadsContainer, false
        )
        row.downloadTitle.text = currentTitle.ifBlank { format.label }
        fetchedVideo?.thumbnail?.takeIf { it.isNotBlank() }?.let {
            ImageHelper.loadImageHighQuality(it, row.downloadThumb)
        }
        row.downloadProgress.isIndeterminate = true
        row.downloadStatus.text = getString(R.string.fetch_starting)
        row.downloadCancel.setOnClickListener { cancelDownload(jobId) }
        row.downloadMore.setOnClickListener { showRowMenu(it, jobId) }
        row.downloadMore.isVisible = false
        row.root.setOnLongClickListener {
            showRowMenu(row.root, jobId)
            true
        }

        binding.downloadsContainer.addView(row.root)
        binding.downloadsSection.isVisible = true
        downloadRows[jobId] = row

        downloadJobs[jobId] = viewLifecycleOwner.lifecycleScope.launch {
            // the engine blocks, so progress is polled from a second coroutine
            val poller = launch {
                while (isActive) {
                    val progress = withContext(Dispatchers.IO) { YtDlpEngine.progress(jobId) }
                    if (progress != null) {
                        updateRow(jobId, progress)
                        if (progress.status in TERMINAL_STATES) break
                    }
                    delay(600)
                }
            }

            // null = failed, "" = succeeded but the engine did not name the file,
            // otherwise the exact path yt-dlp wrote.
            val produced: String? = withContext(Dispatchers.IO) {
                if (format.convertToMp3) {
                    val bitrate = format.formatId.substringAfter("mp3:", "192")
                    YtDlpEngine.startAudioDownload(
                        format.pageUrl, directory.toFile(), jobId, bitrate
                    )
                } else if (format.formatId == "direct") {
                    // A direct URL (Pinterest media, or an InnerTube stream) is
                    // downloaded as-is. InnerTube URLs only answer to the headers
                    // that were used to resolve them, so those go along.
                    val headers = com.atube.app.api.muso.MusoStreamResolver
                        .headersFor(format.directUrl)
                        .let { map ->
                            if (map.isEmpty()) "" else org.json.JSONObject(map as Map<*, *>).toString()
                        }
                    YtDlpEngine.startDownload(
                        format.directUrl, "direct", directory.toFile(), jobId, headers
                    )
                } else {
                    val viaEngine = YtDlpEngine.startDownload(
                        format.pageUrl, format.formatId, directory.toFile(), jobId
                    )
                    // yt-dlp can be refused on a mobile network even though it
                    // extracted fine. The player already falls back to the on-device
                    // InnerTube resolver for exactly this reason, so the download uses
                    // the same route: resolve the video again and fetch the stream it
                    // hands back, with the headers that URL needs.
                    viaEngine ?: run {
                        val videoId = format.pageUrl.toID()
                        val streams = if (videoId.isBlank()) null
                        else com.atube.app.api.muso.MusoStreamResolver.resolve(videoId)

                        val stream = if (format.isAudio) {
                            streams?.audioStreams?.maxByOrNull { it.bitrate ?: 0 }
                        } else {
                            streams?.videoStreams?.maxByOrNull { it.height ?: 0 }
                        }
                        val url = stream?.url
                        if (url.isNullOrBlank()) {
                            null
                        } else {
                            val headers = com.atube.app.api.muso.MusoStreamResolver
                                .headersFor(url)
                                .let { map ->
                                    if (map.isEmpty()) ""
                                    else org.json.JSONObject(map as Map<*, *>).toString()
                                }
                            YtDlpEngine.startDownload(
                                url, "direct", directory.toFile(), jobId, headers
                            )
                        }
                    }
                }
            }
            poller.cancel()

            if (produced != null) {
                registerDownloadedFile(jobId, format, directory, produced)
                // make the finished file visible to the file manager and media apps
                DownloadHelper.notifyMediaScanner(
                    requireContext(),
                    if (produced.isNotBlank()) produced else null
                )
            }
            finishRow(jobId, produced != null)
        }
    }

    /** Long press on a row: play it, or delete it. */
    private fun showRowMenu(anchor: android.view.View, jobId: String) {
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.fetch_play)
        popup.menu.add(0, 2, 1, R.string.fetch_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val videoId = downloadedIds[jobId]
                    if (videoId != null) {
                        NavigationHelper.navigateVideo(
                            requireContext(),
                            PlayerData(videoId = videoId, isOffline = true)
                        )
                    }
                }
                2 -> deleteDownload(jobId)
            }
            true
        }
        popup.show()
    }

    /** Removes a finished download: the row, the database entry and the file. */
    private fun deleteDownload(jobId: String) {
        val row = downloadRows[jobId] ?: return
        val videoId = downloadedIds[jobId]

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (videoId != null) {
                    runCatching {
                        val dao = Database.downloadDao()
                        val entry = dao.getDownloadById(videoId)
                        entry?.downloadItems?.forEach { item ->
                            runCatching { java.io.File(item.path.toString()).delete() }
                            runCatching { dao.deleteDownloadItemById(item.id) }
                        }
                        entry?.let { runCatching { dao.deleteDownload(it.download) } }
                    }
                }
            }
            row.root.animate().alpha(0f).setDuration(160).withEndAction {
                binding.downloadsContainer.removeView(row.root)
                if (binding.downloadsContainer.childCount == 0) {
                    binding.downloadsSection.isVisible = false
                }
            }.start()
            downloadRows.remove(jobId)
            downloadedIds.remove(jobId)
            Snackbar.make(binding.root, R.string.fetch_deleted, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateRow(jobId: String, progress: YtDlpProgress) {
        val row = downloadRows[jobId] ?: return

        if (progress.status == "downloading") {
            row.downloadProgress.isIndeterminate = false
            // Ease the bar to its new value instead of snapping: yt-dlp reports in
            // coarse steps, and jumping 10 to 20 reads as a stutter.
            val target = progress.percent.roundToInt().coerceIn(0, 100)
            val bar = row.downloadProgress
            android.animation.ValueAnimator.ofInt(bar.progress, target).apply {
                duration = 320
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { bar.progress = it.animatedValue as Int }
                start()
            }
            val percent = progress.percent.roundToInt()
            val speed = YtDlpFetchAdapter.formatSize(progress.speedBytesPerSecond)
            row.downloadSize.text = buildString {
                if (progress.totalBytes > 0) {
                    append(YtDlpFetchAdapter.formatSize(progress.downloadedBytes))
                    append(" of ")
                    append(YtDlpFetchAdapter.formatSize(progress.totalBytes))
                } else {
                    append(percent)
                    append('%')
                }
                if (speed.isNotBlank()) {
                    append(" · ")
                    append(speed)
                    append("/s")
                }
            }
            row.downloadStatus.text = buildString {
                append(getString(R.string.fetch_downloading))
                append(" · ")
                append(percent)
                append('%')
                if (speed.isNotBlank()) {
                    append(" · ")
                    append(speed)
                    append("/s")
                }
            }
        } else {
            row.downloadStatus.text = progress.message
        }
    }

    private fun finishRow(jobId: String, ok: Boolean) {
        if (_binding == null) return
        val row = downloadRows[jobId] ?: return

        row.downloadProgress.isIndeterminate = false
        if (ok) row.downloadProgress.progress = 100
        row.downloadStatus.text = getString(
            if (ok) R.string.fetch_saved else R.string.fetch_failed_download
        )
        row.downloadCancel.isVisible = false
        row.downloadMore.isVisible = true
        row.downloadCancel.setImageResource(R.drawable.ic_close)
        row.downloadCancel.setOnClickListener { deleteDownload(jobId) }
        if (ok) row.downloadStatus.text = getString(R.string.fetch_saved)

        YtDlpEngine.cleanup(jobId)
        downloadJobs.remove(jobId)

        val videoId = downloadedIds[jobId]
        if (ok && videoId != null) {
            row.downloadStatus.text = getString(R.string.fetch_saved)
            row.root.setOnClickListener {
                NavigationHelper.navigateVideo(
                    requireContext(),
                    PlayerData(videoId = videoId, isOffline = true)
                )
            }
            Snackbar.make(binding.root, R.string.fetch_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Puts the finished file into the app's download database, so it plays in the
     * normal player and shows up in the Library's Downloads list. The id is
     * prefixed so it can never collide with a real YouTube id.
     */
    private suspend fun registerDownloadedFile(
        jobId: String,
        format: YtDlpResult.Format,
        directory: Path,
        producedPath: String
    ) {
        // Use the file the engine named. Falling back to "newest file in the folder"
        // picked the wrong file whenever more than one download was in flight - the
        // wrong file then failed to play and looked like it was never saved.
        val file = withContext(Dispatchers.IO) {
            val named = producedPath.takeIf { it.isNotBlank() }?.let { File(it) }
            when {
                named != null && named.exists() -> named
                else -> directory.toFile().listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") }
                    ?.maxByOrNull { it.lastModified() }
            }
        } ?: return

        val videoId = "$YTDLP_ID_PREFIX$jobId"
        val dao = Database.downloadDao()

        withContext(Dispatchers.IO) {
            // Type is decided by what landed on disk, not by which button was
            // pressed: an audio-only download registered as VIDEO made the offline
            // player look for a video track that is not there and fail with
            // "Source error".
            val audioExtensions = setOf("m4a", "mp3", "opus", "ogg", "webm", "aac", "wav")
            val isAudioFile = format.isAudio || file.extension.lowercase() in audioExtensions

            // Save the thumbnail alongside the file. A download row could only
            // show a picture if thumbnailPath pointed at something real, and the
            // downloader never wrote one - that is why saved videos had no image.
            val thumbFile = runCatching {
                val url = fetchedVideo?.thumbnail?.takeIf { it.startsWith("http") }
                    ?: return@runCatching null
                // Thumbnails belong to the app, not to the user's folder: keeping them in
                // the download folder left a .jpg beside every song. They live in the
                // app's own cache now, where nothing else sees them.
                val thumbDir = File(requireContext().cacheDir, "thumbs").apply { mkdirs() }
                val out = File(thumbDir, "thumb_${videoId}.jpg")
                java.net.URL(url).openStream().use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.takeIf { it.length() > 0 }
            }.getOrNull()

            dao.insertDownload(
                Download(
                    videoId = videoId,
                    title = currentTitle.ifBlank { file.nameWithoutExtension },
                    thumbnailPath = thumbFile?.toPath(),
                    uploader = fetchedVideo?.uploader.orEmpty()
                )
            )
            dao.insertDownloadItem(
                DownloadItem(
                    type = if (isAudioFile) FileType.AUDIO else FileType.VIDEO,
                    videoId = videoId,
                    fileName = file.name,
                    path = file.toPath(),
                    format = file.extension,
                    downloadSize = file.length()
                )
            )
        }
        downloadedIds[jobId] = videoId
    }

    /**
     * The strip is not session-only: everything the fetch screen downloaded is
     * registered in the download database, so it is re-listed here on open. That
     * is what keeps these files in this section instead of the Library.
     */
    private fun loadPreviousDownloads() {
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
                    .filter { it.download.videoId.startsWith(YTDLP_ID_PREFIX) }
                    .sortedByDescending { it.downloadItems.firstOrNull()?.id ?: 0 }
            }
            if (_binding == null || saved.isEmpty()) return@launch

            saved.forEach { entry ->
                val bytes = entry.downloadItems.sumOf { it.downloadSize }
                    .takeIf { it > 0 }
                    ?: entry.downloadItems.sumOf { runCatching { it.path.fileSize() }.getOrDefault(0L) }
                addSavedRow(entry.download.videoId, entry.download.title, bytes, entry.download.thumbnailPath?.toString())
            }
            binding.downloadsSection.isVisible = true
        }
    }

    private fun addSavedRow(videoId: String, title: String, bytes: Long = 0L, thumbnailPath: String? = null) {
        val row = ItemYtdlpDownloadBinding.inflate(
            LayoutInflater.from(requireContext()), binding.downloadsContainer, false
        )
        row.downloadTitle.text = title.ifBlank { getString(R.string.fetch_saved) }
        // Saved thumbnail is loaded from the local file, so it remains visible offline.
        thumbnailPath?.let { path ->
            val imageFile = File(path)
            if (imageFile.isFile && imageFile.length() > 0L) {
                row.downloadThumb.setImageURI(android.net.Uri.fromFile(imageFile))
            }
        }
        row.downloadProgress.isVisible = false
        row.downloadCancel.isVisible = false
        row.downloadMore.isVisible = true
        row.downloadMore.setOnClickListener { showRowMenu(it, videoId) }
        row.downloadStatus.text = getString(R.string.fetch_saved)
        row.downloadSize.text = if (bytes > 0) YtDlpFetchAdapter.formatSize(bytes) else ""
        row.root.setOnClickListener {
            NavigationHelper.navigateVideo(
                requireContext(),
                PlayerData(videoId = videoId, isOffline = true)
            )
        }
        binding.downloadsContainer.addView(row.root)
    }

    private fun cancelDownload(jobId: String) {
        YtDlpEngine.cancel(jobId)
        downloadJobs.remove(jobId)?.cancel()

        YtDlpEngine.cleanup(jobId)
        downloadRows[jobId]?.apply {
            downloadProgress.isVisible = false
            downloadStatus.text = getString(R.string.fetch_cancelled)
            downloadCancel.isVisible = false
            downloadMore.isVisible = false
        }
    }

    // ---- clipboard + states -------------------------------------------------

    private fun pasteFromClipboard() {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.trim()

        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.fetch_clipboard_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.linkInput.setText("")
        binding.linkInput.alpha = 0.72f
        binding.linkInput.scaleX = 0.985f
        binding.linkInput.animate().cancel()
        binding.linkInput.animate()
            .alpha(1f).scaleX(1f)
            .setDuration(260L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
            .start()
        binding.linkInput.setText(text)
        binding.linkInput.setSelection(binding.linkInput.text?.length ?: 0)
        // A pasted link can be longer than the field keeps, and the emoji2 builder
        // reports a shorter length than the raw string, so setting the cursor to the
        // raw length threw "setSpan ... ends beyond length 5000". Put the cursor at
        // the end of whatever actually landed in the field.
        binding.linkInput.post {
            val editable = binding.linkInput.text ?: return@post
            runCatching { binding.linkInput.setSelection(editable.length) }
        }
    }

    private fun shimmerSkeleton() {
        val blocks = listOf(
            binding.skeletonThumb,
            binding.skeletonLine1,
            binding.skeletonLine2,
            binding.skeletonLine3,
            binding.skeletonRow1,
            binding.skeletonRow2,
            binding.skeletonRow3,
            binding.skeletonRow4
        )
        // The same shimmer the home feed uses, so loading looks the same wherever it
        // happens. Only the handful of blocks on screen are animated, so it stays cheap.
        binding.fetchSkeleton.alpha = 1f
        blocks.forEach { ShimmerHelper.applyShimmer(it, cornerRadiusDp = 10f) }
    }

    private fun showLoading() {
        val skeleton = binding.fetchSkeleton
        skeleton.animate().cancel()
        if (!skeleton.isVisible) {
            skeleton.alpha = 0f
            skeleton.isVisible = true
            skeleton.animate()
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        binding.fetchEmpty.isVisible = false
        binding.previewSection.isVisible = false
    }

    private fun showEmpty(message: String) {
        binding.fetchSkeleton.isVisible = false
        binding.previewSection.isVisible = false
        binding.fetchEmpty.text = message
        binding.fetchEmpty.isVisible = true
        binding.fetchEmptyMessage.isGone = true
    }

    /**
     * One video, one preview: a large thumbnail, its title, then the Video/Audio
     * switch and the qualities that belong to it. No list of videos and no list of
     * every stream - only what can be downloaded for this video.
     */
    private fun showPreview(items: List<YtDlpResult>) {
        val video = items.filterIsInstance<YtDlpResult.Video>().firstOrNull()
        if (video == null) {
            showEmpty(getString(R.string.fetch_no_results))
            return
        }

        binding.fetchSkeleton.isVisible = false
        binding.fetchEmpty.isVisible = false

        fetchedVideo = video
        currentTitle = video.title
        binding.previewTitle.text = video.title
        binding.previewDescription.text = video.uploader.ifBlank {
            getString(R.string.fetch_downloads)
        }

        val density = resources.displayMetrics.density
        val estimatedWidth = (resources.displayMetrics.widthPixels - (68 * density).toInt()).coerceAtLeast(1)
        val width = binding.previewSection.width
            .takeIf { it > 0 }
            ?.let { (it - binding.previewSection.paddingLeft - binding.previewSection.paddingRight).coerceAtLeast(1) }
            ?: estimatedWidth
        val ratio = video.aspect.takeIf { it in 0.12f..8f } ?: (16f / 9f)
        val minHeight = ((if (video.isImage) 80 else 160) * density).toInt()
        val maxHeight = ((if (video.isImage) 560 else 420) * density).toInt()
        val height = (width / ratio).toInt().coerceIn(minHeight, maxHeight)
        binding.previewThumbnail.layoutParams = binding.previewThumbnail.layoutParams.apply {
            this.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            this.height = height
        }
        binding.previewThumbnail.scaleType = if (video.isImage) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
        ImageHelper.loadImageHighQuality(video.thumbnail, binding.previewThumbnail)

        // video: the streams that already carry picture and sound
        videoFormats = items.filterIsInstance<YtDlpResult.Format>()
            .filter { !it.isAudio }
            // One capsule per actual resolution, regardless of codec/container.
            .distinctBy { format ->
                if (format.label.contains("best", ignoreCase = true)) "best"
                else Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                    .find(format.label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.toString()
                    ?: format.label.substringBefore(" · ").lowercase()
            }
            .sortedByDescending { format ->
                Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                    .find(format.label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
            }
        // audio: the same video turned into a song by the bundled ffmpeg
        // The audio tab is where the bitrate is chosen - the video tab never offers
        // one, because there the sound is whatever the site pairs with the picture.
        // Do not advertise MP3 conversion unless the packaged FFmpeg is actually
        // available; yt-dlp's FFmpegExtractAudio postprocessor cannot work without it.
        val audioConversionAvailable = YtDlpEngine.prepare(requireContext()) &&
            YtDlpEngine.ffmpegAvailable()
        audioFormats = if (audioConversionAvailable) listOf("320", "192", "128").map { bitrate ->
            YtDlpResult.Format(
                label = "MP3 · $bitrate kbps",
                detail = getString(R.string.dl_converted_mp3),
                directUrl = "",
                formatId = "mp3:$bitrate",
                pageUrl = video.url,
                isAudio = true,
                convertToMp3 = true
            )
        } else emptyList()

        audioMode = false
        renderMode()
        revealPreview()
    }

    /**
     * The fetched result fades in block by block instead of appearing in one jump:
     * thumbnail, then title, then the mode switch, the qualities and the button.
     */
    private fun revealPreview() {
        val section = binding.previewSection
        section.isVisible = true

        val density = resources.displayMetrics.density
        val blocks = listOfNotNull(
            binding.previewThumbnail,
            binding.previewTitle,
            binding.previewDescription,
            binding.videoMode.parent as? View,
            binding.qualityContainer.parent as? View,
            binding.downloadButton
        )
        blocks.forEachIndexed { index, block ->
            block.animate().cancel()
            block.alpha = 0f
            block.translationY = 16f * density
            block.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 45L)
                .setDuration(280L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun renderMode() {
        val density = resources.displayMetrics.density
        val movieIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_movie)?.mutate()
        val musicIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_music_note)?.mutate()
        val onSelected = android.graphics.Color.WHITE
        val onNormal = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.dl_muted)

        // Only the active capsule carries a label. The inactive capsule stays icon-only.
        binding.videoMode.text = if (audioMode) "" else getString(R.string.dl_mode_video)
        binding.audioMode.text = if (audioMode) getString(R.string.dl_mode_audio) else ""
        movieIcon?.setTint(if (audioMode) onNormal else onSelected)
        musicIcon?.setTint(if (audioMode) onSelected else onNormal)
        binding.videoMode.setCompoundDrawablesRelativeWithIntrinsicBounds(movieIcon, null, null, null)
        binding.audioMode.setCompoundDrawablesRelativeWithIntrinsicBounds(musicIcon, null, null, null)
        binding.videoMode.compoundDrawablePadding = (8 * density).toInt()
        binding.audioMode.compoundDrawablePadding = (8 * density).toInt()
        binding.videoMode.gravity = android.view.Gravity.CENTER
        binding.audioMode.gravity = android.view.Gravity.CENTER

        binding.modeContainer.post {
            if (_binding == null) return@post
            val container = binding.modeContainer
            val indicator = binding.modeIndicator
            val slotWidth = ((container.width - container.paddingLeft - container.paddingRight) / 2).coerceAtLeast(1)
            val params = indicator.layoutParams
            val firstLayout = params.width != slotWidth
            params.width = slotWidth
            params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            indicator.layoutParams = params
            val target = if (audioMode) slotWidth.toFloat() else 0f
            if (firstLayout) indicator.translationX = target else {
                indicator.animate().cancel()
                indicator.animate().translationX(target).setDuration(560L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
            }
        }

        listOf(binding.videoMode, binding.audioMode).forEach { capsule ->
            capsule.animate().cancel()
            capsule.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).withEndAction {
                capsule.animate().scaleX(1f).scaleY(1f).setDuration(260L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.55f)).start()
            }.start()
        }
        binding.videoMode.setTextColor(if (audioMode) onNormal else onSelected)
        binding.audioMode.setTextColor(if (audioMode) onSelected else onNormal)
        binding.videoMode.alpha = if (audioMode) 0.78f else 1f
        binding.audioMode.alpha = if (audioMode) 1f else 0.78f

        val formats = if (audioMode) audioFormats else videoFormats
        // keep what the user picked; only fall back when it is not in this list
        if (formats.none { it == selectedFormat }) selectedFormat = formats.firstOrNull()

        swapQualityList()
    }

    /**
     * Switching Video/Audio swaps the qualities the way a cross-fade does: the old
     * row fades and lifts away, the new one fades in from below while its height
     * grows, so the block never snaps to a different size.
     */
    private fun swapQualityList() {
        val row = binding.qualityContainer
        val lift = row.height / 8f
        row.animate().cancel()

        row.animate()
            .alpha(0f)
            .translationY(-lift)
            .setDuration(210L)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .withEndAction {
                if (_binding == null) return@withEndAction
                fillQualityRow()
                val drop = row.height / 8f
                row.translationY = drop
                row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(390L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.28f))
                    .start()
            }
            .start()
    }

    /** Compact, fully rounded quality capsules arranged responsively in three-item rows. */
    private fun fillQualityRow() {
        runCatching {
        val density = resources.displayMetrics.density
        val formats = if (audioMode) audioFormats else videoFormats
        val container = binding.qualityContainer
        container.removeAllViews()

        formats.chunked(3).forEach { rowFormats ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 0, 0, (8 * density).toInt())
            }
            rowFormats.forEach { format ->
                val chip = LayoutInflater.from(requireContext())
                    .inflate(R.layout.chip_bouncy, row, false) as com.atube.app.ui.views.BouncyChip
                chip.text = qualityLabel(format)
                chip.tag = format
                chip.isSelected = format == selectedFormat
                chip.setOnClickListener { clicked ->
                    selectedFormat = format
                    // Keep the existing chip instances alive while the selected
                    // capsule fluidly moves from the previous quality to this one.
                    for (rowIndex in 0 until container.childCount) {
                        val qualityRow = container.getChildAt(rowIndex) as? LinearLayout ?: continue
                        for (chipIndex in 0 until qualityRow.childCount) {
                            val qualityChip = qualityRow.getChildAt(chipIndex) as? com.atube.app.ui.views.BouncyChip
                                ?: continue
                            qualityChip.isSelected = qualityChip.tag == selectedFormat
                        }
                    }
                    clicked.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    crossFadeDownloadLabel()
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = (8 * density).toInt()
                chip.layoutParams = params
                row.addView(chip)
            }
            container.addView(row)
        }

        binding.downloadButton.isEnabled = formats.isNotEmpty()
        // a cancelled cross-fade could leave the button invisible; it is always shown
        binding.downloadButton.animate().cancel()
        binding.downloadButton.alpha = 1f
        binding.downloadButton.visibility = android.view.View.VISIBLE
        crossFadeDownloadLabel()

        // the capsule switch itself eases across instead of snapping
        binding.videoMode.animate().alpha(if (audioMode) 0.55f else 1f).setDuration(160).start()
        binding.audioMode.animate().alpha(if (audioMode) 1f else 0.55f).setDuration(160).start()
        }
    }

    /** Short, real-source labels keep every quality capsule compact and uncut. */
    private fun qualityLabel(format: YtDlpResult.Format): String {
        val raw = format.label
        val lower = raw.lowercase()
        if (lower.contains("best")) return "Best"
        if (format.isAudio) {
            val bitrate = Regex("(\\d{2,3})\\s*kbps", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.getOrNull(1)
                ?: Regex("(\\d{2,3})").find(raw)?.groupValues?.getOrNull(1)
            return bitrate?.let { "$it kbps" } ?: "Audio"
        }
        val height = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (height != null) return if (height >= 2160) "4K" else "${height}p"
        return raw.substringBefore(" · ").ifBlank { raw }.take(16)
    }

    /** The button's label cross-fades instead of swapping in a single frame. */
    private fun crossFadeDownloadLabel() {
        val label = if (audioMode) {
            getString(R.string.dl_download_audio)
        } else {
            selectedFormat?.let { getString(R.string.dl_download_quality, it.label) }
                ?: getString(R.string.dl_download)
        }
        val button = binding.downloadButton
        button.alpha = 1f
        if (button.text == label) return
        button.animate().cancel()
        button.animate().alpha(0f).setDuration(140L).withEndAction {
            button.text = label
            button.animate().alpha(1f).setDuration(210L).start()
        }.start()
    }

    override fun onDestroyView() {
        fetchJob?.cancel()
        fetchJob = null
        skeletonJob?.cancel()
        skeletonJob = null
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        downloadRows.clear()
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val TERMINAL_STATES = setOf("finished", "error", "cancelled")
    }
}

class DownloadsFragmentAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
    override fun getItemCount() = DownloadTab.entries.size

    override fun createFragment(position: Int): Fragment {
        if (position == DownloadTab.PLAYLIST.ordinal) {
            return PlaylistDownloadsFragmentPage()
        }

        return DownloadsFragmentPage().apply {
            arguments = bundleOf(IntentData.downloadTab to DownloadTab.entries[position])
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
class DownloadsFragmentPage : DynamicLayoutManagerFragment(R.layout.fragment_download_content) {
    private lateinit var adapter: DownloadsAdapter
    private var _binding: FragmentDownloadContentBinding? = null
    private val binding get() = _binding!!

    private val playerViewModel: CommonPlayerViewModel by activityViewModels()
    private val downloadsModel: DownloadsViewModel by activityViewModels()

    private var binder: DownloadService.LocalBinder? = null
    private val downloadReceiver = DownloadReceiver()

    // Either downloadTab or downloadPlaylistId are set, never both at the same time!
    private lateinit var downloadTab: DownloadTab
    private var downloadPlaylistId: String? = null

    private val activeDownloadIds = mutableSetOf<Int>()

    private var selectedSortType
        get() = PreferenceHelper.getInt(
            PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE,
            DownloadSortingOrder.OLDEST.ordinal
        )
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_DOWNLOAD_SORT_TYPE, value)
        }

    private val serviceConnection = object : ServiceConnection {
        var isBound = false
        var job: Job? = null

        override fun onServiceConnected(name: ComponentName?, iBinder: IBinder?) {
            binder = iBinder as DownloadService.LocalBinder
            isBound = true
            job?.cancel()
            job = lifecycleScope.launch {
                binder?.getService()?.downloadFlow?.collectLatest {
                    updateProgress(it.first, it.second)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.downloadTab = requireArguments().serializable(IntentData.downloadTab)!!
        this.downloadPlaylistId = requireArguments().getString(IntentData.playlistId)

        if (downloadTab == DownloadTab.PLAYLIST && downloadPlaylistId == null)
            throw IllegalArgumentException("downloadTab unspecified or missing playlist id")
    }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.downloadsRecView?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        adapter =
            DownloadsAdapter(
                requireContext(), downloadTab, downloadPlaylistId,
                currentSortOrder = {
                    DownloadSortingOrder.entries[selectedSortType]
                }
            ) { !toggleDownload(it) }
        binding.downloadsRecView.adapter = adapter

        val filterOptions = DownloadSortingOrder.entries.map { getString(it.stringId) }
        binding.sortType.text = filterOptions[selectedSortType]

        lifecycleScope.launch(Dispatchers.Main) {
            val playlistItems = downloadPlaylistId?.let { playlistId ->
                val playlist = withContext(Dispatchers.IO) {
                    Database.downloadDao().getDownloadPlaylistById(playlistId)
                }

                binding.playlistName.text = playlist.downloadPlaylist.title
                binding.playlistName.isVisible = true

                playlist.downloadVideos.map { it.videoId }
            }

            val downloads = withContext(Dispatchers.IO) {
                Database.downloadDao().getAll()
            }.let { downloads ->
                // Everything the app downloaded is listed here, including what the
                // Downloader tab's yt-dlp fetch saved, so there is one place to find
                // a downloaded file.
                if (downloadTab != DownloadTab.PLAYLIST) downloads.filterByTab(downloadTab)
                else downloads.filter { playlistItems.orEmpty().contains(it.download.videoId) }
            }

            submitDownloadList(downloads)

            binding.sortType.setOnClickListener {
                BaseBottomSheet().setSimpleItems(filterOptions.toList()) { index ->
                    if (index == selectedSortType) return@setSimpleItems
                    selectedSortType = index

                    binding.sortType.text = filterOptions[index]
                    submitDownloadList(downloads)
                }.show(childFragmentManager)
            }

            downloadsModel.searchQuery.observe(viewLifecycleOwner) {
                submitDownloadList(downloads)
            }

            binding.downloadsRecView.setOnDismissListener { position ->
                adapter.showDeleteDialog(requireContext(), position)
                // put the item back to the center, as it's currently out of the screen
                adapter.restoreItem(position)
            }

            binding.downloadsRecView.adapter?.registerAdapterDataObserver(
                object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        super.onItemRangeRemoved(positionStart, itemCount)
                        toggleVisibilities()
                    }

                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        // ensure that after searching with no results,
                        // the nothing here placeholder is hidden again so that
                        // results are visible for the queries made afterwards
                        toggleVisibilities()
                    }
                }
            )

            toggleVisibilities()
        }

        binding.resumeAll.setOnClickListener {
            resumeAllDownloads()

            binding.resumeAll.isGone = true
        }

        binding.deleteAll.setOnClickListener {
            showDeleteAllDialog(binding.root.context, adapter)
        }

        binding.shuffleAll.setOnClickListener {
            NavigationHelper.navigateVideo(
                requireContext(),
                playerData = PlayerData(
                    videoId = null,
                    playlistId = downloadPlaylistId,
                    downloadTab = downloadTab,
                    shuffle = true,
                    isOffline = true,
                ),
                audioOnlyPlayerRequested = downloadTab == DownloadTab.AUDIO
            )
        }

        playerViewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            binding.shuffleAll.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = (if (isMiniPlayerVisible) 64f else 16f).dpToPx()
            }
        }
    }

    private fun toggleDownload(download: DownloadWithItems): Boolean {
        val ids = download.downloadItems
            .filter { item -> item.path.fileSize() < item.downloadSize }
            .map { item -> item.id }

        if (!serviceConnection.isBound) {
            DownloadHelper.startDownloadService(requireContext())
            bindDownloadService(ids.toIntArray())
            return true
        }

        binder?.getService()?.let { service ->
            val isDownloading = ids.any { id -> service.isDownloading(id) }

            for (id in ids) {
                if (isDownloading) {
                    service.pause(id)
                } else {
                    service.resume(id)
                }
            }

            return isDownloading
        }
        return false
    }

    private fun resumeAllDownloads() {
        val intent = Intent(requireContext(), DownloadService::class.java)
        intent.action = DownloadService.ACTION_RESUME_ALL
        requireContext().startService(intent)

        bindDownloadService()
    }

    private fun submitDownloadList(items: List<DownloadWithItems>) {
        val sortOrder = DownloadSortingOrder.entries[selectedSortType]
        var sortedItems = sortDownloadWithItemsList(items, sortOrder)
        val query = downloadsModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            sortedItems = sortedItems.filter {
                it.download.title.contains(query, ignoreCase = true)
                        || it.download.uploader.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(sortedItems)
    }

    private fun toggleVisibilities() {
        val binding = _binding ?: return

        val isEmpty = adapter.itemCount == 0
        val hasIncomplete = adapter.currentList.any { download ->
            download.downloadItems.any { !it.isFinished } &&
                    download.downloadItems.none { activeDownloadIds.contains(it.id) }
        }
        binding.downloadsEmpty.isVisible = isEmpty
        binding.downloadsContainer.isGone = isEmpty
        binding.deleteAll.isGone = isEmpty
        binding.resumeAll.isVisible = hasIncomplete
        binding.shuffleAll.isGone = isEmpty
    }

    private fun showDeleteAllDialog(context: Context, adapter: DownloadsAdapter) {
        var onlyDeleteWatchedVideos = false

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.delete_all)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.delete_only_watched_videos)),
                null
            ) { _, _, selected ->
                onlyDeleteWatchedVideos = selected
            }
            .setPositiveButton(R.string.okay) { _, _ ->
                adapter.deleteAllDownloads(onlyDeleteWatchedVideos)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        if (DownloadService.IS_DOWNLOAD_RUNNING) bindDownloadService()

        super.onStart()
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_SERVICE_STARTED)
            addAction(DownloadService.ACTION_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Attach to running [DownloadService]. This should be called whenever it's possible that the service
     * was stopped, so the connection has to be re-initiated.
     */
    fun bindDownloadService(ids: IntArray? = null) {
        if (serviceConnection.isBound) return

        val intent = Intent(context, DownloadService::class.java)
        intent.putExtra("ids", ids)
        context?.bindService(intent, serviceConnection, 0)
    }

    fun updateProgress(id: Int, status: DownloadStatus) {
        val index = adapter.currentList.indexOfFirst {
            it.downloadItems.any { item -> item.id == id }
        }
        val view =
            _binding?.downloadsRecView?.findViewHolderForAdapterPosition(index) as? DownloadsViewHolder

        if (status is DownloadStatus.Progress) activeDownloadIds.add(id)
        else activeDownloadIds.remove(id)

        view?.binding?.apply {
            when (status) {
                DownloadStatus.Paused -> {
                    resumePauseBtn.setImageResource(R.drawable.ic_download)
                }

                DownloadStatus.Completed -> {
                    downloadOverlay.isGone = true
                }

                DownloadStatus.Stopped -> Unit

                is DownloadStatus.Progress -> {
                    downloadOverlay.isVisible = true
                    resumePauseBtn.setImageResource(R.drawable.ic_pause)
                    if (progressBar.isIndeterminate) return
                    progressBar.incrementProgressBy(status.progress.toInt())
                    val progressInfo = progressBar.progress.formatAsFileSize() +
                            " /\n" + progressBar.max.formatAsFileSize()
                    fileSize.text = progressInfo
                }

                is DownloadStatus.Error -> {
                    resumePauseBtn.setImageResource(R.drawable.ic_restart)
                }
            }
        }

        toggleVisibilities()
    }

    override fun onPause() {
        super.onPause()
        context?.unregisterReceiver(downloadReceiver)
    }

    override fun onStop() {
        super.onStop()
        runCatching {
            context?.unbindService(serviceConnection)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun sortDownloadWithItemsList(
            items: List<DownloadWithItems>,
            selectedSortType: DownloadSortingOrder
        ): List<DownloadWithItems> {
            return when (selectedSortType) {
                DownloadSortingOrder.OLDEST -> items
                DownloadSortingOrder.NEWEST -> items.reversed()
                DownloadSortingOrder.ALPHABETIC -> items.sortedBy { it.download.title }
                DownloadSortingOrder.DURATION -> items.sortedBy { it.download.duration }
                DownloadSortingOrder.CHANNEL -> items.sortedBy { it.download.uploader }
                DownloadSortingOrder.SIZE -> items.sortedBy { it.downloadItems.sumOf { o -> o.downloadSize } }
            }
        }

        // ugly HACK: should probably be refactored in the future
        fun sortDownloadList(
            items: List<Download>,
            selectedSortType: DownloadSortingOrder
        ): List<Download> {
            return when (selectedSortType) {
                DownloadSortingOrder.OLDEST -> items
                DownloadSortingOrder.NEWEST -> items.reversed()
                DownloadSortingOrder.ALPHABETIC -> items.sortedBy { it.title }
                DownloadSortingOrder.DURATION -> items.sortedBy { it.duration }
                DownloadSortingOrder.CHANNEL -> items.sortedBy { it.uploader }
                DownloadSortingOrder.SIZE -> items
            }
        }
    }
}

class PlaylistDownloadsFragmentPage : Fragment(R.layout.fragment_download_content) {
    private var selectedSortType
        get() = PreferenceHelper.getInt(PreferenceKeys.SELECTED_DOWNLOAD_PLAYLIST_SORT_TYPE, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.SELECTED_DOWNLOAD_PLAYLIST_SORT_TYPE, value)
        }
    private val downloadsModel: DownloadsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentDownloadContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.shuffleAll.isGone = true

        var backPressedCallback: OnBackPressedCallback? = null
        backPressedCallback = setOnBackPressed {
            childFragmentManager.fragments.firstOrNull()?.let {
                childFragmentManager.commit { remove(it) }
            }
            backPressedCallback?.isEnabled = false
        }
        backPressedCallback.isEnabled = false

        val adapter = DownloadPlaylistAdapter { playlist ->
            childFragmentManager.commit {
                replace<DownloadsFragmentPage>(
                    binding.fragment.id,
                    args = bundleOf(
                        IntentData.downloadTab to DownloadTab.PLAYLIST,
                        IntentData.playlistId to playlist.downloadPlaylist.playlistId
                    )
                )
            }
            backPressedCallback.isEnabled = true
        }
        binding.downloadsRecView.setOnDismissListener { position ->
            adapter.showDeleteDialog(requireContext(), position)
            // put the item back to the center, as it's currently out of the screen
            adapter.restoreItem(position)
        }
        binding.downloadsRecView.adapter = adapter

        val filterOptions = DownloadSortingOrder.entries.map { getString(it.stringId) }
        binding.sortType.text = filterOptions[selectedSortType]

        lifecycleScope.launch(Dispatchers.Main) {
            val downloadPlaylists = withContext(Dispatchers.IO) {
                Database.downloadDao().getDownloadPlaylists()
            }

            binding.downloadsEmpty.isVisible = downloadPlaylists.isEmpty()
            if (downloadPlaylists.isNotEmpty()) {
                submitPlaylists(adapter, downloadPlaylists)

                binding.sortType.setOnClickListener {
                    BaseBottomSheet().setSimpleItems(filterOptions.toList()) { index ->
                        if (index == selectedSortType) return@setSimpleItems
                        selectedSortType = index

                        binding.sortType.text = filterOptions[index]
                        submitPlaylists(adapter, downloadPlaylists)
                    }.show(childFragmentManager)
                }

                downloadsModel.searchQuery.observe(viewLifecycleOwner) {
                    submitPlaylists(adapter, downloadPlaylists)
                }

                // the amount of visible items can change while searching, hence
                // we have to make sure to only show the placeholder if there are currently no results
                adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                        super.onItemRangeRemoved(positionStart, itemCount)
                        binding.downloadsEmpty.isVisible = adapter.itemCount == 0
                        binding.downloadsRecView.isVisible = adapter.itemCount != 0
                    }

                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        binding.downloadsEmpty.isVisible = adapter.itemCount == 0
                        binding.downloadsRecView.isVisible = adapter.itemCount != 0
                    }
                })
            } else {
                binding.sortType.isGone = true
            }
        }
    }

    private fun submitPlaylists(
        adapter: DownloadPlaylistAdapter,
        playlists: List<DownloadPlaylistWithDownload>
    ) {
        var sorted = applySortOrder(playlists)
        val query = downloadsModel.searchQuery.value
        if (!query.isNullOrEmpty()) {
            sorted = sorted.filter {
                it.downloadPlaylist.title.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(sorted)
    }

    fun applySortOrder(items: List<DownloadPlaylistWithDownload>): List<DownloadPlaylistWithDownload> {
        return when (selectedSortType) {
            0 -> items
            else -> items.reversed()
        }
    }

}
