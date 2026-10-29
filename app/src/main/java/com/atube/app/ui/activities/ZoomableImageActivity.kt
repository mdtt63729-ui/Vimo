package com.atube.app.ui.activities

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.atube.app.constants.IntentData
import com.atube.app.databinding.ActivityZoomableImageBinding
import com.atube.app.helpers.ImageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An activity that allows you to zoom and rotate an image
 */
class ZoomableImageActivity : AppCompatActivity() {
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityZoomableImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmapUrl = intent.getStringExtra(IntentData.bitmapUrl)!!
            val bitmap = ImageHelper.getImage(this@ZoomableImageActivity, bitmapUrl) ?: return@launch

            withContext(Dispatchers.Main) {
                binding.imageView.setImageBitmap(bitmap)
                binding.progress.isGone = true
                binding.imageView.isVisible = true
            }
        }
    }
}
