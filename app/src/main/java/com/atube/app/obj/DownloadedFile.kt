package com.atube.app.obj

import android.graphics.Bitmap
import com.atube.app.api.obj.Streams

data class DownloadedFile(
    val name: String,
    val size: Long,
    var metadata: Streams? = null,
    var thumbnail: Bitmap? = null
)
