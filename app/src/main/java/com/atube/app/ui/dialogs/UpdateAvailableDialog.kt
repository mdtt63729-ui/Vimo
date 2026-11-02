package com.atube.app.ui.dialogs

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import com.atube.app.R
import com.atube.app.databinding.DialogUpdateBinding
import com.atube.app.constants.IntentData.appUpdateChangelog
import com.atube.app.constants.IntentData.appUpdateURL
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class UpdateAvailableDialog : BottomSheetDialogFragment() {
    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!

    private var changelog: String? = null
    private var releaseUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            changelog = getString(appUpdateChangelog)
            releaseUrl = getString(appUpdateURL)
        }
    }

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
        binding.updateVersion.text = getString(R.string.update_available)
        binding.updateNotes.text = changelog.orEmpty().ifBlank {
            getString(R.string.update_title)
        }

        view.alpha = 0f
        view.translationY = 18f * resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(360L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        binding.downloadUpdate.setOnClickListener {
            releaseUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
            dismissSmoothly()
        }
        binding.laterUpdate.setOnClickListener { dismissSmoothly() }
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
}
