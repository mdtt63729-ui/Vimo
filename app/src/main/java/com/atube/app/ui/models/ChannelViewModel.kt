package com.atube.app.ui.models

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.atube.app.api.obj.Channel
import com.atube.app.api.obj.StreamItem

class ChannelViewModel : ViewModel() {
    val channelResponse = MutableLiveData<Channel?>(null)

    // These are used for the first tab ("Videos") if they were pre-fetched with the channel
    var relatedStreams: List<StreamItem>? = null
    var nextPage: String? = null
}
