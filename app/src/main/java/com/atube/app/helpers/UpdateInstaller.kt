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
import androidx.core.content.FileProvider
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
                        androidx.core.graphics.drawable.toBitmap(
                            androidx.core.content.ContextCompat.getDrawable(
                                context, R.mipmap.ic_launcher
                            )!!
                        )
                    )
                    .setBigContentTitle(context.getString(R.string.update_notification_title))
            )
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    /** Starts the APK download; installation follows automatically when it lands. */
    fun download(context: Context, apkUrl: String, version: String) {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(dir, "Vimo-$version.apk")
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(context.getString(R.string.update_downloading))
            .setDestinationUri(Uri.fromFile(target))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setMimeType("application/vnd.android.package-archive")

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = runCatching { manager.enqueue(request) }.getOrNull() ?: return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                runCatching { ctx.unregisterReceiver(this) }
                if (target.exists()) install(ctx, target)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
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
