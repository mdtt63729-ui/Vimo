package com.atube.app.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.atube.app.R
import com.atube.app.api.obj.StreamItem
import com.atube.app.constants.IntentData
import com.atube.app.databinding.FragmentShortsBinding
import com.atube.app.extensions.toID
import com.atube.app.helpers.DownloadHelper
import com.atube.app.ui.adapters.ShortsPagerAdapter
import com.atube.app.ui.dialogs.ShareDialog
import com.atube.app.ui.models.CommentsViewModel
import com.atube.app.ui.models.ShortsViewModel
import com.atube.app.ui.sheets.CommentsSheet

/**
 * Shorts fragment with continuous vertical paging.
 *
 * Performance optimizations:
 * - Minimal page transformer (only alpha, no scale/translation) for 60fps
 * - ViewPager2 offscreenPageLimit=1 (only 1 preloaded page)
 * - Input disabled during fast scroll to prevent accidental taps
 * - RecyclerView prefetch enabled for next page
 */
class ShortsFragment : Fragment(R.layout.fragment_shorts) {
    private var _binding: FragmentShortsBinding? = null
    private val binding get() = _binding!!

    private val shortsViewModel: ShortsViewModel by activityViewModels()
    private val commentsViewModel: CommentsViewModel by activityViewModels()
    private lateinit var adapter: ShortsPagerAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentShortsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        setupAdapter()
        setupViewPager()
        observeViewModel()

        if (shortsViewModel.shorts.value.isNullOrEmpty()) {
            shortsViewModel.loadShorts(requireContext())
        }
    }

    private fun setupAdapter() {
        adapter = ShortsPagerAdapter(
            onShareClick = { item -> showShareDialog(item) },
            onDownloadClick = { videoId -> startDownload(videoId) },
            onCommentClick = { videoId -> openComments(videoId) },
            onChannelClick = { uploaderUrl -> openChannel(uploaderUrl) },
            onLikeClick = { item, isLiked -> },
            onSubscribeClick = { uploaderUrl, isSubscribed -> },
            onSkipToNext = {
                val nextPos = binding.shortsViewPager.currentItem + 1
                if (nextPos < adapter.itemCount) {
                    binding.shortsViewPager.setCurrentItem(nextPos, true)
                }
            }
        )
    }

    private fun setupViewPager() {
        binding.shortsViewPager.apply {
            this.adapter = this@ShortsFragment.adapter
            offscreenPageLimit = 1

            // Lightweight page transformer — only alpha for maximum 60fps
            // No scale or translation to avoid extra GPU layer compositing
            setPageTransformer { page, position ->
                page.alpha = (1f - kotlin.math.abs(position) * 0.35f).coerceAtLeast(0f)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    this@ShortsFragment.adapter.onPageSelected(position)

                    val itemCount = this@ShortsFragment.adapter.itemCount
                    if (position >= itemCount - 3 && shortsViewModel.isLoading.value != true) {
                        shortsViewModel.loadMoreShorts()
                    }
                }
            })
        }
    }

    private fun observeViewModel() {
        shortsViewModel.shorts.observe(viewLifecycleOwner) { shorts ->
            if (shorts.isNotEmpty()) {
                binding.shortsLoading.isVisible = false
                binding.shortsError.isVisible = false
                adapter.submitList(shorts)
            }
        }

        shortsViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading && adapter.itemCount == 0) {
                binding.shortsLoading.isVisible = true
            } else {
                binding.shortsLoading.isVisible = false
            }
        }

        shortsViewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg != null && adapter.itemCount == 0) {
                binding.shortsLoading.isVisible = false
                binding.shortsError.isVisible = true
                binding.shortsErrorText.text = errorMsg
            } else {
                binding.shortsError.isVisible = false
            }
        }

        binding.shortsRetry.setOnClickListener {
            shortsViewModel.clear()
            shortsViewModel.loadShorts(requireContext())
        }
    }

    private fun showShareDialog(item: StreamItem) {
        ShareDialog().apply {
            arguments = android.os.Bundle().apply {
                putString(IntentData.id, item.url?.toID())
            }
        }.show(childFragmentManager, ShareDialog::class.java.name)
    }

    private fun startDownload(videoId: String) {
        DownloadHelper.startDownloadDialog(requireContext(), childFragmentManager, videoId.toID())
    }

    private fun openComments(videoId: String) {
        commentsViewModel.videoIdLiveData.value = videoId
        CommentsSheet().show(childFragmentManager, CommentsSheet::class.java.name)
    }

    private fun openChannel(uploaderUrl: String?) {
        if (uploaderUrl.isNullOrEmpty()) return
        val channelId = uploaderUrl.toID()
        val args = bundleOf(IntentData.channelId to channelId)
        findNavController().navigate(R.id.openChannel, args)
    }

    override fun onPause() {
        super.onPause()
        adapter.pauseCurrentPlayer()
    }

    override fun onResume() {
        super.onResume()
        adapter.resumeCurrentPlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        adapter.releasePlayer()
        _binding = null
    }
}
