package com.atube.app.helpers

import android.content.Context
import com.atube.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Asks the GitHub releases endpoint what the newest build is, and whether it is
 * newer than the one installed. Nothing is downloaded here.
 */
object UpdateChecker {

    /**
     * Master switch for the in-app update popup.
     *
     * It is off: no build shows an update sheet, and nothing asks GitHub for a release.
     * Flip this to true when updates should start appearing again - the popup, the
     * download and the install are all still here and work; only this gate keeps them
     * quiet.
     */
    const val UPDATES_ENABLED = true

    private const val RELEASES_URL =
        "https://api.github.com/repos/mdtt63729-ui/Vimo/releases/latest"

    data class Update(val version: String, val apkUrl: String, val notes: String)

    private val client = OkHttpClient()

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        // Silent while updates are switched off: no request, no sheet, no notification.
        if (!UPDATES_ENABLED) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = JSONObject(response.body?.string().orEmpty())
                val tag = body.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                if (tag.isBlank()) return@runCatching null

                val asset = body.optJSONArray("assets")
                    ?.let { assets ->
                        (0 until assets.length())
                            .map { assets.optJSONObject(it) }
                            .firstOrNull { it?.optString("name")?.endsWith(".apk") == true }
                    } ?: return@runCatching null

                val url = asset.optString("browser_download_url")
                if (url.isBlank()) return@runCatching null
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@runCatching null

                Update(
                    version = tag,
                    apkUrl = url,
                    notes = body.optString("body").lineSequence().take(6).joinToString("\n")
                )
            }
        }.getOrNull()
    }

    /** true when [remote] is a higher version than [local], comparing part by part. */
    private fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.').mapNotNull { it.toIntOrNull() }
        val b = local.split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
