package com.atube.app.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.atube.app.R
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

object UpdateInstaller {
    private const val CHANNEL_ID = "vimo_updates"
    private const val NOTIFICATION_ID = 4201
    private const val PREFS = "vimo_update_download"
    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Progress(
        val started: Boolean,
        val complete: Boolean,
        val paused: Boolean,
        val indeterminate: Boolean,
        val percent: Int,
        val speedBytesPerSecond: Long
    )

    @Volatile private var pauseRequested = false
    @Volatile private var activeVersion: String? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var currentSpeed = 0L
    @Volatile private var unknownLength = true

    fun notifyAvailable(context: Context, version: String) {
        ensureChannel(context)
        val intent = Intent(context, com.atube.app.ui.activities.MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, version))
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(
                ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap()))
            .setAutoCancel(true).setContentIntent(pending).build()
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    fun download(context: Context, apkUrl: String, version: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val part = File(dir, "Vimo-$version.apk.part")
        val final = File(dir, "Vimo-$version.apk")
        if (final.exists() && final.length() > 0) return
        prefs.edit().putString("version", version).putString("url", apkUrl)
            .putBoolean("started", true).putBoolean("paused", false).apply()
        pauseRequested = false
        activeVersion = version
        if (worker?.isAlive == true) return
        worker = Thread {
            try {
                var offset = part.length()
                val requestBuilder = Request.Builder().url(apkUrl)
                    .header("Accept", "application/octet-stream")
                if (offset > 0) requestBuilder.header("Range", "bytes=$offset-")
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty update response")
                    if (offset > 0 && response.code != 206) {
                        part.delete()
                        offset = 0L
                    }
                    val total = if (body.contentLength() > 0) body.contentLength() + offset else -1L
                    unknownLength = total <= 0
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(part, offset > 0).use { out ->
                                val buffer = ByteArray(32 * 1024)
                                var lastTime = System.nanoTime()
                                var lastBytes = offset
                                while (true) {
                                    while (pauseRequested) {
                                        prefs.edit().putBoolean("paused", true).apply()
                                        Thread.sleep(250)
                                        if (activeVersion != version) throw InterruptedException("Update request changed")
                                    }
                                    prefs.edit().putBoolean("paused", false).apply()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                    val now = System.nanoTime()
                                    if (now - lastTime >= 500_000_000L) {
                                        currentSpeed = ((part.length() - lastBytes) * 1_000_000_000L /
                                            (now - lastTime)).coerceAtLeast(0)
                                        lastTime = now
                                        lastBytes = part.length()
                                    }
                                    prefs.edit().putLong("total", total).putLong("downloaded", part.length()).apply()
                                }
                            }
                    }
                }
                if (part.exists() && part.length() > 0 && !pauseRequested) {
                    if (!part.renameTo(final)) part.copyTo(final, overwrite = true).also { part.delete() }
                    prefs.edit().putBoolean("complete", true).putBoolean("paused", false)
                        .putLong("downloaded", final.length()).putLong("total", final.length()).apply()
                    Handler(Looper.getMainLooper()).post { install(app, final) }
                }
            } catch (_: Exception) {
                prefs.edit().putBoolean("paused", true).apply()
            } finally {
                currentSpeed = 0L
                worker = null
            }
        }.apply { name = "VimoUpdateDownload"; start() }
    }

    fun pause(context: Context, version: String) {
        pauseRequested = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("paused", true).apply()
    }

    fun resume(context: Context, version: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("url", null) ?: return
        download(context, url, version)
    }

    fun getProgress(context: Context, version: String): Progress {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("version", null) != version) return Progress(false, false, false, false, 0, 0)
        val complete = prefs.getBoolean("complete", false)
        val started = prefs.getBoolean("started", false)
        val downloaded = prefs.getLong("downloaded", 0)
        val total = prefs.getLong("total", -1)
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        return Progress(started, complete, prefs.getBoolean("paused", false),
            started && !complete && (total <= 0 || downloaded == 0L), percent, currentSpeed)
    }

    fun installSaved(context: Context, version: String) {
        val file = File(File(context.getExternalFilesDir(null), "updates"), "Vimo-$version.apk")
        if (file.exists() && file.length() > 0) install(context, file)
    }

    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        // ACTION_INSTALL_PACKAGE is the request the package installer actually answers;
        // ACTION_VIEW on an APK can open a chooser that goes nowhere, which is the
        // "shows Install but nothing happens" report.
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { context.startActivity(intent) }.isSuccess
        if (!started) {
            // fall back to the view intent for installers that only accept that
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }
}
