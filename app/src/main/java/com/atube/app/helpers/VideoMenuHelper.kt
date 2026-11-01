package com.atube.app.helpers

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import com.atube.app.R
import com.atube.app.api.obj.StreamItem
import com.atube.app.constants.IntentData
import com.atube.app.extensions.toID
import com.atube.app.ui.dialogs.AddToPlaylistDialog
import com.atube.app.ui.dialogs.DownloadDialog
import com.atube.app.ui.views.VideoMenuPopup
import com.atube.app.util.PlayingQueue

/**
 * The three-dot menu for any video row, wherever it appears: search results, watch
 * history, the player's suggestions. It works off the row's own context, so a list
 * does not have to hand a callback down through every adapter.
 */
object VideoMenuHelper {

    fun show(anchor: View, video: StreamItem) {
        val activity = anchor.context as? FragmentActivity ?: return
        val videoId = video.url?.toID().orEmpty()
        if (videoId.isEmpty()) return

        VideoMenuPopup(anchor) { action ->
            when (action) {
                VideoMenuPopup.Action.PLAY_NEXT -> {
                    PlayingQueue.addAsNext(video)
                    toast(anchor, R.string.menu_queued_next)
                }
                VideoMenuPopup.Action.PLAY_LAST -> {
                    PlayingQueue.add(video)
                    toast(anchor, R.string.menu_queued_last)
                }
                VideoMenuPopup.Action.DOWNLOAD -> DownloadDialog().apply {
                    arguments = bundleOf(IntentData.videoId to videoId)
                }.show(activity.supportFragmentManager, DownloadDialog::class.java.name)

                VideoMenuPopup.Action.SAVE -> AddToPlaylistDialog().apply {
                    arguments = bundleOf(IntentData.videoInfo to video)
                }.show(activity.supportFragmentManager, AddToPlaylistDialog::class.java.name)

                VideoMenuPopup.Action.SHARE, VideoMenuPopup.Action.REPORT -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "https://www.youtube.com/watch?v=$videoId"
                        )
                    }
                    anchor.context.startActivity(
                        Intent.createChooser(intent, anchor.context.getString(R.string.menu_share))
                    )
                }
                VideoMenuPopup.Action.NOT_INTERESTED,
                VideoMenuPopup.Action.DONT_RECOMMEND -> {
                    com.atube.app.recommendation.feedback.RecommendationFeedbackManager
                        .markNotInterested(videoId)
                    video.uploaderUrl?.toID()?.let {
                        com.atube.app.recommendation.feedback.RecommendationFeedbackManager
                            .blockChannel(it)
                    }
                    toast(anchor, R.string.menu_hidden)
                }
            }
        }.show()
    }

    private fun toast(anchor: View, message: Int) {
        Toast.makeText(anchor.context, message, Toast.LENGTH_SHORT).show()
    }
}
