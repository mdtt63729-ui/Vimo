package com.atube.app.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.atube.app.R
import com.atube.app.databinding.DialogUpdateBinding
import com.atube.app.helpers.UpdateChecker
import com.atube.app.helpers.UpdateInstaller

/**
 * The update prompt uses the same native bottom-sheet presentation as the other
 * Vimo sheets, entering smoothly from the bottom. It can be dismissed because a new build
 * is not a reason to stop someone using the app they already have.
 */
class UpdateSheetFragment : BottomSheetDialogFragment() {

    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        playEntrance(view)
        val version = arguments?.getString(ARG_VERSION).orEmpty()
        val apkUrl = arguments?.getString(ARG_URL).orEmpty()
        val notes = arguments?.getString(ARG_NOTES).orEmpty()

        binding.updateVersion.text = getString(R.string.update_version, version)
        binding.updateNotes.text = notes.ifBlank { getString(R.string.update_title) }

        binding.downloadUpdate.setOnClickListener {
            UpdateInstaller.download(requireContext(), apkUrl, version)
            dismissSmoothly()
        }
        binding.laterUpdate.setOnClickListener { dismissSmoothly() }
    }

    private fun playEntrance(root: View) {
        val density = resources.displayMetrics.density
        root.animate().cancel()
        root.alpha = 0f
        root.translationY = 18f * density
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(360L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun dismissSmoothly() {
        val root = _binding?.root ?: run { dismissAllowingStateLoss(); return }
        root.animate()
            .alpha(0f)
            .translationY(40f * resources.displayMetrics.density)
            .setDuration(200L)
            .withEndAction { dismissAllowingStateLoss() }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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

    private fun Int?.orZero(): Int = this ?: 0
}
