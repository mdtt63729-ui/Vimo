package com.atube.app.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.atube.app.R
import com.atube.app.databinding.DialogUpdateBinding
import com.atube.app.helpers.UpdateChecker
import com.atube.app.helpers.UpdateInstaller
import java.util.Locale

class UpdateSheetFragment : BottomSheetDialogFragment() {

    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isDownloading = false
    private var isPaused = false
    private var apkUrl = ""
    private var version = ""

    private val progressPoll = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            val state = UpdateInstaller.getProgress(requireContext(), version)
            if (state.complete) {
                showCompleted()
                return
            }
            if (state.started) {
                showProgress(state.percent, state.speedBytesPerSecond, state.indeterminate)
                isDownloading = true
                isPaused = state.paused
                b.resumeUpdate.text = getString(if (isPaused) R.string.update_resume else R.string.update_pause)
                handler.postDelayed(this, 400)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.navigationBarColor = android.graphics.Color.TRANSPARENT
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.isFitToContents = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        version = args.getString(ARG_VERSION).orEmpty()
        apkUrl = args.getString(ARG_URL).orEmpty()
        binding.updateVersion.text = getString(R.string.update_version, version)
        binding.updateNotes.text = args.getString(ARG_NOTES).orEmpty().ifBlank { getString(R.string.update_title) }

        val existing = UpdateInstaller.getProgress(requireContext(), version)
        when {
            existing.complete -> showCompleted()
            existing.started -> {
                showProgress(existing.percent, existing.speedBytesPerSecond, existing.indeterminate)
                isDownloading = true
                isPaused = existing.paused
                binding.resumeUpdate.text = getString(if (isPaused) R.string.update_resume else R.string.update_pause)
                handler.post(progressPoll)
            }
            else -> showIntro()
        }

        binding.downloadUpdate.setOnClickListener {
            showProgress(0, 0L, true)
            isDownloading = true
            UpdateInstaller.download(requireContext(), apkUrl, version)
            handler.post(progressPoll)
            expandSheet()
        }
        binding.laterUpdate.setOnClickListener { dismissSmoothly() }
        binding.resumeUpdate.setOnClickListener {
            if (isPaused) UpdateInstaller.resume(requireContext(), version)
            else UpdateInstaller.pause(requireContext(), version)
            isPaused = !isPaused
            binding.resumeUpdate.text = getString(if (isPaused) R.string.update_resume else R.string.update_pause)
        }
        binding.exitUpdate.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.update_exit_title)
                .setMessage(R.string.update_exit_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.update_exit) { _, _ ->
                    UpdateInstaller.pause(requireContext(), version)
                    dismissSmoothly()
                }.show()
        }
        binding.installUpdate.setOnClickListener { UpdateInstaller.installSaved(requireContext(), version) }
        playEntrance(view)
    }

    private fun showIntro() = with(binding) {
        updateNotes.isVisible = true
        downloadUpdate.isVisible = true
        laterUpdate.isVisible = true
        progressArea.isVisible = false
        installUpdate.isVisible = false
    }

    private fun showProgress(percent: Int, speed: Long, indeterminate: Boolean) = with(binding) {
        updateNotes.isVisible = false
        downloadUpdate.isVisible = false
        laterUpdate.isVisible = false
        installUpdate.isVisible = false
        progressArea.isVisible = true
        updateProgress.isIndeterminate = indeterminate
        if (!indeterminate) {
            updateProgress.progress = percent.coerceIn(0, 100)
            updatePercent.text = getString(R.string.update_percent, percent.coerceIn(0, 100))
        } else {
            updatePercent.text = getString(R.string.update_preparing)
        }
        updateSpeed.text = if (speed > 0) "${formatSpeed(speed)}" else ""
        resumeUpdate.isVisible = true
        exitUpdate.isVisible = true
        expandSheet()
    }

    private fun showCompleted() = with(binding) {
        updateNotes.isVisible = false
        downloadUpdate.isVisible = false
        laterUpdate.isVisible = false
        progressArea.isVisible = false
        resumeUpdate.isVisible = false
        exitUpdate.isVisible = false
        installUpdate.isVisible = true
        expandSheet()
        UpdateInstaller.installSaved(requireContext(), version)
    }

    private fun formatSpeed(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb >= 1024) String.format(Locale.getDefault(), "%.1f MB/s", kb / 1024)
        else String.format(Locale.getDefault(), "%.0f KB/s", kb)
    }

    private fun expandSheet() {
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun playEntrance(root: View) {
        val d = resources.displayMetrics.density
        root.animate().cancel()
        root.alpha = 0f
        root.translationY = 12f * d
        root.animate().alpha(1f).translationY(0f).setDuration(190L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
    }

    private fun dismissSmoothly() {
        val root = _binding?.root ?: run { dismissAllowingStateLoss(); return }
        root.animate().alpha(0f).translationY(24f * resources.displayMetrics.density)
            .setDuration(140L).withEndAction { dismissAllowingStateLoss() }.start()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_VERSION = "version"
        private const val ARG_URL = "url"
        private const val ARG_NOTES = "notes"
        const val TAG = "update_sheet"

        fun newInstance(update: UpdateChecker.Update) = UpdateSheetFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_VERSION, update.version)
                putString(ARG_URL, update.apkUrl)
                putString(ARG_NOTES, update.notes)
            }
        }
    }
}
