package com.atube.app.helpers

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.atube.app.R
import com.atube.app.api.PlaylistsHelper
import com.atube.app.constants.IntentData
import com.atube.app.constants.PreferenceKeys
import com.atube.app.db.DatabaseHolder
import com.atube.app.db.obj.DownloadItem
import com.atube.app.db.obj.DownloadWithItems
import com.atube.app.enums.FileType
import com.atube.app.enums.PlaylistType
import com.atube.app.extensions.toID
import com.atube.app.extensions.toastFromMainDispatcher
import com.atube.app.parcelable.DownloadData
import com.atube.app.services.DownloadService
import com.atube.app.ui.dialogs.DownloadDialog
import com.atube.app.ui.dialogs.DownloadPlaylistDialog
import com.atube.app.ui.dialogs.ShareDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div

object DownloadHelper {
    const val VIDEO_DIR = "yt-downloader"
    const val AUDIO_DIR = "yt-downloader"

    /** What the Downloader page saves, kept apart from the app's own downloads. */
    const val DOWNLOADER_DIR = "downloader"
    const val SUBTITLE_DIR = "subtitle"
    const val THUMBNAIL_DIR = "thumbnail"
    const val PLAYLIST_THUMBNAIL_DIR = "playlist_thumbnail"
    const val DOWNLOAD_CHUNK_SIZE = 8L * 1024
    const val DEFAULT_TIMEOUT = 15 * 1000
    const val MAX_CONCURRENT_DOWNLOADS = 6
    private const val VIDEO_MIMETYPE = "video/*"

    /**
     * Everything the app saves lives under a single Vimo folder on the phone's storage:
     * /storage/emulated/0/Vimo/<path>. "downloader" holds what came from the Downloader
     * page, "yt-downloader" holds what the app itself fetched. If that public folder
     * cannot be written - scoped storage on a newer Android - it falls back to the app's
     * own external folder so a download never fails for want of a directory.
     */
    /**
     * Creates /storage/emulated/0/Vimo and nothing inside it: every download - from the
     * Downloader page or from the app itself - is written straight into that one folder.
     * Returns true when the folder is really writable on this device.
     */
    fun ensureVimoFolder(): Boolean {
        val root = runCatching {
            android.os.Environment.getExternalStorageDirectory().resolve("Vimo")
        }.getOrNull() ?: return false

        return runCatching {
            root.mkdirs()
            root.isDirectory && root.canWrite()
        }.getOrDefault(false)
    }

    /** True when this device can actually write to the public Vimo folder. */
    fun hasFolderAccess(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /**
     * Tells Android's media database that a file exists. Without this the file is on
     * disk but the file manager, the gallery and music apps cannot see it - which is
     * why downloads were missing from the file manager even though they had been saved.
     */
    fun notifyMediaScanner(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context.applicationContext, arrayOf(path), null, null
            )
        }
    }

    /** Deletes a downloaded file from the phone's storage, if it is still there. */
    fun deleteFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            val file = java.io.File(path)
            file.exists() && file.delete()
        }.getOrDefault(false)
    }

    /**
     * The folder every download goes into: /storage/emulated/0/Vimo, with no subfolders.
     * The [path] argument is kept so existing callers do not change, but it is not used
     * to nest anything - the user asked for one flat folder.
     *
     * Falls back to the app's own external folder only when the public one truly cannot
     * be written, so a download never fails for want of a directory.
     */
    fun getDownloadDir(context: Context, path: String): Path {
        val root = runCatching {
            android.os.Environment.getExternalStorageDirectory().resolve("Vimo")
        }.getOrNull()

        if (root != null && runCatching {
                root.mkdirs()
                root.isDirectory && root.canWrite()
            }.getOrDefault(false)
        ) {
            return root.toPath()
        }

        val storageDir = try {
            context.getExternalFilesDir(null)!!
        } catch (e: Exception) {
            context.filesDir
        }
        return storageDir.toPath().createDirectories()
    }

    fun startDownloadService(context: Context, downloadData: DownloadData? = null) {
        val intent = Intent(context, DownloadService::class.java)
            .putExtra(IntentData.downloadData, downloadData)

        ContextCompat.startForegroundService(context, intent)
    }

    fun DownloadItem.getNotificationId(): Int {
        return Int.MAX_VALUE - id
    }

    fun startDownloadDialog(context: Context, fragmentManager: FragmentManager, videoId: String) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            DownloadDialog().apply {
                arguments = bundleOf(IntentData.videoId to videoId)
            }.show(fragmentManager, DownloadDialog::class.java.name)
        } else {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=$videoId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        }
    }

    fun startDownloadPlaylistDialog(
        context: Context,
        fragmentManager: FragmentManager,
        playlistId: String,
        playlistName: String,
        playlistType: PlaylistType
    ) {
        val externalProviderPackageName =
            PreferenceHelper.getString(PreferenceKeys.EXTERNAL_DOWNLOAD_PROVIDER, "")

        if (externalProviderPackageName.isBlank()) {
            val downloadPlaylistDialog = DownloadPlaylistDialog().apply {
                arguments = bundleOf(
                    IntentData.playlistId to playlistId,
                    IntentData.playlistName to playlistName,
                    IntentData.playlistType to playlistType
                )
            }
            downloadPlaylistDialog.show(fragmentManager, null)
        } else if (playlistType == PlaylistType.PUBLIC) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setPackage(externalProviderPackageName)
                .setDataAndType(
                    "${ShareDialog.YOUTUBE_FRONTEND_URL}/playlist?list=$playlistId".toUri(),
                    VIDEO_MIMETYPE
                )

            runCatching { context.startActivity(intent) }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val playlistVideoIds = try {
                    PlaylistsHelper.getPlaylist(playlistId)
                } catch (e: Exception) {
                    context.toastFromMainDispatcher(R.string.unknown_error)
                    return@launch
                }.relatedStreams.mapNotNull { it.url?.toID() }.joinToString(",")

                val intent = Intent(Intent.ACTION_VIEW)
                    .setPackage(externalProviderPackageName)
                    .setDataAndType(
                        "${ShareDialog.YOUTUBE_FRONTEND_URL}/watch_videos?video_ids=${playlistVideoIds}".toUri(),
                        VIDEO_MIMETYPE
                    )

                withContext(Dispatchers.Main) {
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    fun extractDownloadInfoText(context: Context, download: DownloadWithItems): List<String> {
        val downloadInfo = mutableListOf<String>()
        download.downloadItems.firstOrNull { it.type == FileType.VIDEO }?.let { videoItem ->
            downloadInfo.add(context.getString(R.string.video) + ": ${videoItem.format} ${videoItem.quality}")
        }
        download.downloadItems.firstOrNull { it.type == FileType.AUDIO }?.let { audioItem ->
            var infoString = ": ${audioItem.quality} ${audioItem.format})"
            if (audioItem.language != null) infoString += " ${audioItem.language}"
            downloadInfo.add(context.getString(R.string.audio) + infoString)
        }
        download.downloadItems.firstOrNull { it.type == FileType.SUBTITLE }?.let {
            downloadInfo.add(context.getString(R.string.captions) + ": ${it.language}")
        }
        return downloadInfo
    }

    suspend fun deleteDownloadIncludingFiles(downloadWithItems: DownloadWithItems) {
        val download = downloadWithItems.download
        val items = downloadWithItems.downloadItems

        items.forEach {
            it.path.deleteIfExists()
        }
        runCatching {
            download.thumbnailPath?.deleteIfExists()
        }

        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.downloadDao().deleteDownload(download)
        }
    }
}
