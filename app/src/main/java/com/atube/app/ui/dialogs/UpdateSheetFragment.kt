package com.atube.app.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.atube.app.R
import com.atube.app.databinding.DialogUpdateBinding
import com.atube.app.helpers.UpdateChecker
import com.atube.app.helpers.UpdateInstaller

/**
 * The update prompt. It drops in from the top - the mirror image of the Telegram
 * sheet, which rises from the bottom - and can be dismissed, because a new build
 * is not a reason to stop someone using the app they already have.
 */
class UpdateSheetFragment : DialogFragment() {

    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_update, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val density = resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = -56f * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(360L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.85f))
            .start()

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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

    private fun dismissSmoothly() {
        val root = _binding?.root ?: run { dismissAllowingStateLoss(); return }
        root.animate()
            .alpha(0f)
            .translationY(-40f * resources.displayMetrics.density)
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
}
