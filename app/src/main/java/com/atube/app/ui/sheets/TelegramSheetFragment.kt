package com.atube.app.ui.sheets

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.atube.app.R
import com.atube.app.databinding.FragmentTelegramSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The join-the-channel sheet. It cannot be swiped away, tapped away or backed out
 * of: the app is gated on it, so the only way past it is Join Telegram.
 */
class TelegramSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentTelegramSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTelegramSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.apply {
            behavior.isDraggable = false
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }

        binding.joinButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)))
            }
            setFragmentResult(REQUEST_KEY, bundleOf(JOINED to true))
            dismissAllowingStateLoss()
        }

        binding.laterButton.setOnClickListener {
            // The app stays locked until the channel is joined, so this does not
            // close the sheet - it just says why.
            Toast.makeText(requireContext(), R.string.tg_must_join, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY = "telegram_sheet_request"
        const val JOINED = "joined"
        private const val TELEGRAM_URL = "https://t.me/Aether_OS_app_store"
    }
}
