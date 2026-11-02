package com.atube.app.helpers

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.atube.app.R
import java.io.File

/**
 * Downloads the new APK with DownloadManager and hands it to the installer, and
 * posts the "new version" notification with the app logo as its picture.
 */
object UpdateInstaller {

    private const val CHANNEL_ID = "vimo_updates"
    private const val NOTIFICATION_ID = 4201

    fun notifyAvailable(context: Context, version: String) {
        ensureChannel(context)
        val intent = Intent(context, com.atube.app.ui.activities.MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, version))
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(
                        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap()
                    )
            )
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Downloads the APK and hands it to the installer.
     *
     * This used to go through DownloadManager with setDestinationUri, which quietly
     * does nothing for a path on the app's external files dir on modern Android - the
     * popup closed and no file ever appeared. The stream is written here instead, so
     * the progress is ours and the file is definitely there at the end.
     */
    fun download(context: Context, apkUrl: String, version: String) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val dir = File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val target = File(dir, "Vimo-$version.apk")
                if (target.exists()) target.delete()

                val request = okhttp3.Request.Builder()
                    .url(apkUrl)
                    .header("Accept", "application/octet-stream")
                    .build()

                okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@runCatching
                        val body = response.body ?: return@runCatching
                        body.byteStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }

                if (target.exists() && target.length() > 0) {
                    android.os.Handler(app.mainLooper).post { install(app, target) }
                }
            }
        }.start()
    }

    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
