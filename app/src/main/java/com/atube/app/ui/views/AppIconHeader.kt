package com.atube.app.ui.views

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.atube.app.BuildConfig
import com.atube.app.databinding.AppIconHeaderBinding
import com.atube.app.helpers.ClipboardHelper
import com.atube.app.ui.activities.AboutActivity.Companion.GITHUB_URL

class AppIconHeader(context: Context, attributeSet: AttributeSet? = null) :
    LinearLayout(context, attributeSet) {
        val binding = AppIconHeaderBinding.inflate(LayoutInflater.from(context), this, true)

        init {
            // Version text — no debug/release label, just version name and code
            val versionText = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            binding.versionTv.text = versionText
            binding.versionCard.setOnClickListener {
                ClipboardHelper.save(context, text = versionText, notify = true)
            }

            // Long press on developer photo to share GitHub link
            binding.devPhoto.setOnLongClickListener {
                val sendIntent = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, GITHUB_URL)
                    .setType("text/plain")

                context.startActivity(Intent.createChooser(sendIntent, null))
                true
            }
        }
}
