package com.atube.app.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.atube.app.R
import com.atube.app.databinding.FragmentDownloadsLibraryBinding
import com.google.android.material.tabs.TabLayoutMediator

/**
 * The downloaded-files library.
 *
 * This is the screen the Library section's Downloads button opens. It only lists
 * files the app itself downloaded through its own downloader - anything pulled
 * from the Downloader tab's yt-dlp fetch stays there, under the link box.
 *
 * Tapping an entry plays it in the same player the rest of the app uses, because
 * the pages below hand the file to the normal player fragment.
 */
class DownloadsLibraryFragment : Fragment(R.layout.fragment_downloads_library) {
    private var _binding: FragmentDownloadsLibraryBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentDownloadsLibraryBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.libraryPager.adapter = DownloadsFragmentAdapter(this)

        TabLayoutMediator(binding.libraryTabs, binding.libraryPager) { tab, position ->
            tab.text = when (position) {
                DownloadTab.VIDEO.ordinal -> getString(R.string.video)
                DownloadTab.AUDIO.ordinal -> getString(R.string.audio)
                DownloadTab.PLAYLIST.ordinal -> getString(R.string.playlists)
                else -> throw IllegalArgumentException()
            }
        }.attach()
    }

    /** Mirrors the old behaviour: hand the running download service to the pages. */
    fun bindDownloadService() {
        childFragmentManager.fragments.filterIsInstance<DownloadsFragmentPage>().forEach {
            it.bindDownloadService()
        }
        childFragmentManager.fragments.filterIsInstance<PlaylistDownloadsFragmentPage>().forEach {
            it.bindDownloadService()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
