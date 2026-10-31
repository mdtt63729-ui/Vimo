package com.atube.app.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.atube.app.R
import com.atube.app.api.ytdlp.YtDlpEngine
import com.atube.app.databinding.FragmentConverterBinding
import com.atube.app.db.DatabaseHolder.Database
import com.atube.app.extensions.formatAsFileSize
import com.atube.app.helpers.DownloadHelper
import com.atube.app.ui.adapters.ConverterFile
import com.atube.app.ui.adapters.ConverterFileAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.nameWithoutExtension

/**
 * The converter.
 *
 * Pick one of the app's downloaded files, pick a bitrate, press Convert, and the
 * audio is re-encoded to MP3 by the ffmpeg bundled with the app. Everything runs
 * on the device.
 *
 * The bundled ffmpeg is a deliberately small LGPL build that carries exactly one
 * encoder - libmp3lame - so MP3 is the only target offered. Adding AAC or WAV
 * means enabling more encoders in scripts/build-ffmpeg-android.sh.
 */
class ConverterFragment : Fragment(R.layout.fragment_converter) {
    private var _binding: FragmentConverterBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ConverterFileAdapter
    private var selected: ConverterFile? = null
    private var job: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentConverterBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        adapter = ConverterFileAdapter { file ->
            selected = file
            adapter.setSelected(file.videoId)
            binding.convertButton.isEnabled = true
        }
        binding.converterFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.converterFiles.adapter = adapter

        binding.convertButton.setOnClickListener { convert() }

        loadFiles()
    }

    private fun bitrate(): String = when (binding.bitrateChips.checkedChipId) {
        R.id.bitrate128 -> "128k"
        R.id.bitrate256 -> "256k"
        R.id.bitrate320 -> "320k"
        else -> "192k"
    }

    private fun loadFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { readFiles() }
            if (_binding == null) return@launch

            adapter.submitList(files)
            binding.converterEmpty.isVisible = files.isEmpty()
            binding.converterFiles.isVisible = files.isNotEmpty()
        }
    }

    private suspend fun readFiles(): List<ConverterFile> {
        val downloads = Database.downloadDao().getAll()
        val out = mutableListOf<ConverterFile>()
        downloads.forEach { entry ->
            entry.downloadItems
                .filter { it.path.exists() }
                .forEach { item ->
                    out += ConverterFile(
                        videoId = entry.download.videoId,
                        title = entry.download.title.ifBlank { item.fileName },
                        meta = listOf(
                            item.path.fileSize().formatAsFileSize(),
                            item.format ?: item.path.nameWithoutExtension
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        path = item.path
                    )
                }
        }
        return out.sortedByDescending { it.path.fileSize() }
    }

    private fun convert() {
        val file = selected ?: return
        val bitrate = bitrate()

        val outputDir = DownloadHelper.getDownloadDir(
            requireContext(), DownloadHelper.AUDIO_DIR
        )
        val output = outputDir.resolve(
            file.path.nameWithoutExtension + " - " + bitrate.replace("k", "") + "k.mp3"
        )

        job?.cancel()
        binding.converterProgress.isVisible = true
        binding.converterStatus.isVisible = true
        binding.converterStatus.text = getString(R.string.converter_working)
        binding.convertButton.isEnabled = false

        job = viewLifecycleOwner.lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                YtDlpEngine.convertAudio(file.path.toString(), output.toString(), bitrate)
            }
            if (_binding == null) return@launch

            binding.converterProgress.isVisible = false
            binding.convertButton.isEnabled = true

            if (json.optBoolean("ok")) {
                binding.converterStatus.text =
                    getString(R.string.converter_done, output.fileName.toString())
                Snackbar.make(binding.root, R.string.converter_done_short, Snackbar.LENGTH_SHORT)
                    .show()
            } else {
                val message = json.optString("error").ifBlank { getString(R.string.converter_failed) }
                binding.converterStatus.text = message
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        job = null
        super.onDestroyView()
        _binding = null
    }
}
