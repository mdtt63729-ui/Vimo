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

    private val downloadRows = mutableMapOf<String, ItemYtdlpDownloadBinding>()
    /** jobId -> videoId of the row registered in the download database. */
    private val downloadedIds = mutableMapOf<String, String>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private var currentTitle = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        adapter = YtDlpFetchAdapter(
            onVideoClick = { video -> if (video.url.isNotBlank()) fetchLink(video.url) },
            onFormatDownload = { format -> startDownload(format) }
        )
        binding.fetchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.fetchResults.adapter = adapter

        binding.fetchButton.setOnClickListener { startFetch() }
        binding.linkInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startFetch()
                true
            } else {
                false
            }
        }
        binding.linkInputLayout.setEndIconOnClickListener { pasteFromClipboard() }

        shimmerSkeleton()
        showEmpty(getString(R.string.fetch_empty_message))
        loadPreviousDownloads()
    }

    fun bindDownloadService() = Unit

    // ---- fetching -----------------------------------------------------------

    private fun startFetch() {
        val input = binding.linkInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            binding.linkInputLayout.error = getString(R.string.fetch_need_input)
            return
        }
        binding.linkInputLayout.error = null

        if (input.startsWith("http://") || input.startsWith("https://")) {
            fetchLink(input)
        } else {
            runSearch(input)
        }
    }

    private fun fetchLink(url: String) {
        runFetch {
            val json = YtDlpEngine.extractInfo(url)
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(
                    json.optString("error").ifBlank { getString(R.string.fetch_failed) }
                )
            }
            itemsFromInfo(json)
        }
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

    private fun runFetch(block: () -> List<YtDlpResult>) {
        fetchJob?.cancel()
        showLoading()

        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { block() } }
            if (_binding == null) return@launch

            outcome.onSuccess { items ->
                if (items.isEmpty()) {
                    showEmpty(getString(R.string.fetch_no_results))
                } else {
                    adapter.submitList(items)
                    showResults()
                }
            }.onFailure { error ->
                showEmpty(error.message ?: getString(R.string.fetch_failed))
            }
        }
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

        // One-tap best quality. Merging separate video and audio streams needs the
        // bundled ffmpeg; without it yt-dlp would fail, so fall back to the best
        // single file that already carries both.
        val canMerge = YtDlpEngine.ffmpegAvailable()
        out += YtDlpResult.Format(
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
        if (canMerge) {
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

            val label = if (hasVideo) "$resolution · $ext" else "Audio · $ext"
            val kind = when {
                hasVideo && hasAudio -> "Video + audio"
                hasVideo -> "Video only"
                else -> "Audio only"
            }
            val size = YtDlpFetchAdapter.formatSize(format.optLong("filesize"))
            val detail = listOf(kind, size).filter { it.isNotBlank() }.joinToString(" · ")

            out += YtDlpResult.Format(
                label = label,
                detail = detail,
                directUrl = direct,
                formatId = formatId,
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
        val directory = DownloadHelper.getDownloadDir(
            requireContext(),
            if (format.isAudio) DownloadHelper.AUDIO_DIR else DownloadHelper.VIDEO_DIR
        )

        val row = ItemYtdlpDownloadBinding.inflate(
            LayoutInflater.from(requireContext()), binding.downloadsContainer, false
        )
        row.downloadTitle.text = currentTitle.ifBlank { format.label }
        row.downloadProgress.isIndeterminate = true
        row.downloadStatus.text = getString(R.string.fetch_starting)
        row.downloadCancel.setOnClickListener { cancelDownload(jobId) }

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

            val ok = withContext(Dispatchers.IO) {
                if (format.convertToMp3) {
                    YtDlpEngine.startAudioDownload(format.pageUrl, directory.toFile(), jobId)
                } else {
                    YtDlpEngine.startDownload(
                        format.pageUrl, format.formatId, directory.toFile(), jobId
                    )
                }
            }
            poller.cancel()

            if (ok) registerDownloadedFile(jobId, format, directory)
            finishRow(jobId, ok)
        }
    }

    private fun updateRow(jobId: String, progress: YtDlpProgress) {
        val row = downloadRows[jobId] ?: return

        if (progress.status == "downloading") {
            row.downloadProgress.isIndeterminate = false
            row.downloadProgress.progress = progress.percent.roundToInt().coerceIn(0, 100)
            val percent = progress.percent.roundToInt()
            val speed = YtDlpFetchAdapter.formatSize(progress.speedBytesPerSecond)
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
        directory: Path
    ) {
        val file = withContext(Dispatchers.IO) {
            directory.toFile().listFiles()?.maxByOrNull { it.lastModified() }
        } ?: return

        val videoId = "$YTDLP_ID_PREFIX$jobId"
        val dao = Database.downloadDao()

        withContext(Dispatchers.IO) {
            dao.insertDownload(
                Download(
                    videoId = videoId,
                    title = currentTitle.ifBlank { file.nameWithoutExtension }
                )
            )
            dao.insertDownloadItem(
                DownloadItem(
                    type = if (format.isAudio) FileType.AUDIO else FileType.VIDEO,
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
                addSavedRow(entry.download.videoId, entry.download.title)
            }
            binding.downloadsSection.isVisible = true
        }
    }

    private fun addSavedRow(videoId: String, title: String) {
        val row = ItemYtdlpDownloadBinding.inflate(
            LayoutInflater.from(requireContext()), binding.downloadsContainer, false
        )
        row.downloadTitle.text = title.ifBlank { getString(R.string.fetch_saved) }
        row.downloadProgress.isVisible = false
        row.downloadCancel.isVisible = false
        row.downloadStatus.text = getString(R.string.fetch_saved)
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

        downloadRows[jobId]?.apply {
            downloadProgress.isVisible = false
            downloadStatus.text = getString(R.string.fetch_cancelled)
            downloadCancel.isVisible = false
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
        binding.linkInput.setText(text)
        binding.linkInput.setSelection(text.length)
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
        blocks.forEach { ShimmerHelper.applyShimmer(it, cornerRadiusDp = 10f) }
    }

    private fun showLoading() {
        binding.fetchSkeleton.isVisible = true
        binding.fetchEmpty.isVisible = false
        binding.fetchResults.isVisible = false
    }

    private fun showResults() {
        binding.fetchSkeleton.isVisible = false
        binding.fetchEmpty.isVisible = false
        binding.fetchResults.isVisible = true
    }

    private fun showEmpty(message: String) {
        binding.fetchSkeleton.isVisible = false
        binding.fetchResults.isVisible = false
        binding.fetchEmpty.isVisible = true
        binding.fetchEmptyMessage.text = message
    }

    override fun onDestroyView() {
        fetchJob?.cancel()
        fetchJob = null
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
                // Files pulled from the Downloader tab's yt-dlp fetch belong to
                // that screen, not here; they are registered in the database only
                // so they can play in the app's own player.
                val appDownloads = downloads.filterNot { it.download.videoId.startsWith(YTDLP_ID_PREFIX) }
                if (downloadTab != DownloadTab.PLAYLIST) appDownloads.filterByTab(downloadTab)
                else appDownloads.filter { playlistItems.orEmpty().contains(it.download.videoId) }
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