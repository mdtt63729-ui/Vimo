package com.atube.app.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.atube.app.R
import com.atube.app.api.PlaylistsHelper
import com.atube.app.constants.IntentData
import com.atube.app.databinding.DialogCreatePlaylistBinding
import com.atube.app.extensions.toastFromMainDispatcher
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CreatePlaylistDialog : com.google.android.material.bottomsheet.BottomSheetDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogCreatePlaylistBinding.inflate(layoutInflater)

        binding.createPlaylistSpinner.items = listOf(
            getString(R.string.createNewPlaylist),
            getString(R.string.clonePlaylist)
        )
        binding.createPlaylistSpinner.setOnSelectionChangeListener { position ->
            binding.createPlayistContainerView.isVisible = position == 0
            binding.clonePlaylistContainerView.isVisible = position == 1
        }

        binding.clonePlaylist.setOnClickListener {
            val playlistUrl = binding.playlistUrl.text.toString().toHttpUrlOrNull()
            val appContext = context?.applicationContext

            playlistUrl?.queryParameter("list")?.let {
                lifecycleScope.launch {
                    requireDialog().hide()
                    val playlistId = withContext(Dispatchers.IO) {
                        runCatching {
                            PlaylistsHelper.clonePlaylist(it)
                        }.getOrNull()
                    }
                    if (playlistId != null) {
                        setFragmentResult(
                            CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
                            bundleOf(
                                IntentData.playlistTask to true,
                                IntentData.playlistId to playlistId
                            ),
                        )
                    }
                    appContext?.toastFromMainDispatcher(
                        if (playlistId != null) R.string.playlistCloned else R.string.server_error
                    )
                    dismiss()
                }
            } ?: run {
                Toast.makeText(context, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            }
        }

        binding.createNewPlaylist.setOnClickListener {
            val appContext = context?.applicationContext
            val listName = binding.playlistName.text?.toString()
            if (!listName.isNullOrEmpty()) {
                // avoid creating the same playlist multiple times by spamming the button
                binding.createNewPlaylist.setOnClickListener(null)
                lifecycleScope.launch {
                    requireDialog().hide()
                    val playlistId = withContext(Dispatchers.IO) {
                        runCatching {
                            PlaylistsHelper.createPlaylist(listName)
                        }.getOrNull()
                    }
                    appContext?.toastFromMainDispatcher(
                        if (playlistId != null) R.string.playlistCreated else R.string.unknown_error
                    )
                    playlistId?.let {
                        setFragmentResult(
                            CREATE_PLAYLIST_DIALOG_REQUEST_KEY,
                            bundleOf(
                                IntentData.playlistTask to true,
                                IntentData.playlistId to playlistId
                            )
                        )
                    }
                    dismiss()
                }
            } else {
                Toast.makeText(context, R.string.emptyPlaylistName, Toast.LENGTH_LONG).show()
            }
        }

        binding.cancelCreate.setOnClickListener {
            dialog?.cancel()
        }

        binding.cancelClone.setOnClickListener {
            dialog?.cancel()
        }

        // Opens from the bottom as a sheet rather than a centred dialog, so the name
        // field sits near the keyboard. The content is wrapped in a scroll view so
        // the fields can be reached even when the keyboard takes the lower half.
        val scroll = NestedScrollView(requireContext()).apply {
            isFillViewport = true
            addView(binding.root)
        }

        // This one comes down from the top, and eases in rather than appearing.
        // The PRD's sheet, from the bottom: a fixed height that never changes while it
        // is open, a scrim that fades in with it, and the blocks rising into place one
        // after another (120ms + 40ms per block, capped at eight, 350ms each).
        val sheet = BottomSheetDialog(requireContext()).apply {
            setContentView(scroll)
            behavior.isFitToContents = false
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            // fixed height: animating it to fit content is what breaks this design
            behavior.peekHeight = (512 * resources.displayMetrics.density)
                .toInt()
                .coerceAtMost((resources.displayMetrics.heightPixels * 0.72f).toInt())
        }

        // While the name is being typed the sheet takes the whole screen, so the
        // fields are never hidden behind the keyboard; it settles back when focus goes.
        val nameField = scroll.findViewById<android.widget.EditText>(R.id.playlistName)
        nameField?.setOnFocusChangeListener { _, hasFocus ->
            sheet.behavior.state = if (hasFocus) {
                BottomSheetBehavior.STATE_EXPANDED
            } else {
                BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        val animate = animatorScaleEnabled()
        if (animate) {
            val density = resources.displayMetrics.density
            val container = binding.root as? android.view.ViewGroup
            val blocks = (0 until (container?.childCount ?: 0)).mapNotNull { container?.getChildAt(it) }
            blocks.take(8).forEachIndexed { index, block ->
                block.alpha = 0f
                // one and a half times its own height, as the reference does
                block.translationY = block.height * 1.5f
                block.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(120L + index * 40L)
                    .setDuration(350L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }

        return sheet
    }

    companion object {
        const val CREATE_PLAYLIST_DIALOG_REQUEST_KEY = "create_playlist_dialog_request_key"
    }

    /** The PRD requires the system animator scale to be honoured: zero means no motion. */
    private fun animatorScaleEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }.getOrDefault(true)
}
