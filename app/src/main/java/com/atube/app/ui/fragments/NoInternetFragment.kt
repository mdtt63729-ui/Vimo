package com.atube.app.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.atube.app.R
import com.atube.app.databinding.FragmentNointernetBinding
import com.atube.app.helpers.NavigationHelper
import com.atube.app.helpers.NetworkHelper
import com.google.android.material.snackbar.Snackbar

class NoInternetFragment: Fragment(R.layout.fragment_nointernet) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = FragmentNointernetBinding.bind(view)
        binding.retryButton.setOnClickListener {
            if (NetworkHelper.isNetworkAvailable(requireContext())) {
                NavigationHelper.restartMainActivity(requireContext())
            } else {
                Snackbar.make(binding.root, R.string.turnInternetOn, Snackbar.LENGTH_LONG).show()
            }
        }

        binding.downloads.setOnClickListener {
            // The library's downloads, not the Downloader tab. This screen sits in its own
            // navigation graph, where downloadsLibraryFragment does not exist - asking for
            // it there threw IllegalArgumentException and took the app down. If the
            // destination is not in this graph, the main app is opened instead.
            val navigated = runCatching {
                findNavController().navigate(R.id.downloadsLibraryFragment)
            }.isSuccess
            if (!navigated) {
                com.atube.app.helpers.NavigationHelper.restartMainActivity(requireContext())
            }
        }
    }
}