package com.atube.app.ui.sheets

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.atube.app.R
import com.atube.app.api.obj.Subscription
import com.atube.app.constants.PreferenceKeys
import com.atube.app.databinding.SheetSubscriptionsBinding
import com.atube.app.extensions.toID
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.ui.adapters.SubscriptionChannelAdapter
import com.atube.app.ui.models.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class SubscriptionsBottomSheet : ExpandedBottomSheet(R.layout.sheet_subscriptions) {
    private var _binding: SheetSubscriptionsBinding? = null
    private val binding get() = _binding!!
    private val adapter = SubscriptionChannelAdapter()

    private val selectedChannelGroup
        get() = PreferenceHelper.getInt(PreferenceKeys.SELECTED_CHANNEL_GROUP, -1)

    private val searchInputText
        get() = binding.subscriptionsSearchInput.text.toString()

    private val viewModel: SubscriptionsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = SheetSubscriptionsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.channelsRecycler.adapter = adapter

        binding.subscriptionsSearchInput.addTextChangedListener { _ ->
            showFilteredSubscriptions()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.IO) {
                    viewModel.fetchSubscriptions(requireContext())
                }

                launch {
                    combine(
                        viewModel.subscriptions.asFlow(),
                        viewModel.groups.asFlow(),
                    ) { subscriptions, groups ->
                        subscriptions to groups
                    }
                        .flowOn(Dispatchers.IO)
                        .collectLatest {
                            initHeaderLayout()
                            showFilteredSubscriptions()
                        }
                }
            }
        }
    }

    private fun getUngroupedChannels(): List<Subscription> {
        val groups = viewModel.groups.value.orEmpty()

        return viewModel.subscriptions.value.orEmpty().filter { subscription ->
            groups.none { it.channels.contains(subscription.url) }
        }
    }

    private fun initHeaderLayout() {
        @SuppressLint("StringFormatInvalid")
        binding.allSubsBtn.text =
            "%s (%d)".format(
                requireContext().getString(R.string.subscriptions),
                viewModel.subscriptions.value?.size ?: 0
            )
        binding.allSubsBtn.setOnClickListener {
            binding.groupEditBtn.isVisible = false

            showFilteredSubscriptions()
        }

        // "ungrouped" category is hidden if the user doesn't use channel groups
        if (!viewModel.groups.value.isNullOrEmpty()) {
            binding.ungroupedSubsBtn.text = requireContext().getString(
                R.string.ungrouped_channels_with_count,
                getUngroupedChannels().size
            )
            binding.ungroupedSubsBtn.setOnClickListener {
                binding.groupEditBtn.isVisible = false

                showFilteredSubscriptions()
            }
            binding.ungroupedSubsBtn.isVisible = getUngroupedChannels().isNotEmpty()
        }

        viewModel.groups.value?.getOrNull(selectedChannelGroup)?.let { channelGroup ->
            @SuppressLint("StringFormatInvalid")
            binding.allSubsBtn.text =
                "%s (%d)".format(
                    requireContext().getString(R.string.all),
                    viewModel.subscriptions.value?.size ?: 0
                )

            binding.groupSubsBtn.isVisible = true
            binding.groupSubsBtn.isChecked = true
            binding.groupSubsBtn.text = "%s (%d)".format(
                channelGroup.name,
                channelGroup.channels.size
            )
            binding.groupSubsBtn.setOnClickListener {
                binding.groupEditBtn.isVisible = true

                showFilteredSubscriptions()
            }

            binding.groupEditBtn.isVisible = true
            binding.groupEditBtn.setOnClickListener {
                viewModel.groupToEdit = channelGroup
                EditChannelGroupSheet()
                    .show(parentFragmentManager, null)
            }

            // refresh displayed list of channels when channel groups have been edited
            if (binding.groupSubsBtn.isChecked) {
                showFilteredSubscriptions()
            }
        }
    }

    private fun showFilteredSubscriptions() {
        val loweredQuery = searchInputText.trim().lowercase()

        val shouldFilterByGroup = binding.groupSubsBtn.isChecked
        val shouldFilterUngrouped = binding.ungroupedSubsBtn.isChecked
        val filteredSubscriptions = viewModel.subscriptions.value.orEmpty()
            .filterByGroup(
                when {
                    shouldFilterByGroup -> selectedChannelGroup
                    shouldFilterUngrouped -> -2
                    else -> -1 // all
                }
            )
            .filter { it.name.lowercase().contains(loweredQuery) }

        adapter.submitList(filteredSubscriptions)
    }

    private fun List<Subscription>.filterByGroup(groupIndex: Int): List<Subscription> {
        if (groupIndex == -1) return this
        if (groupIndex == -2) return getUngroupedChannels()

        val group = viewModel.groups.value?.getOrNull(groupIndex)
            ?: return this

        return filter { group.channels.contains(it.url.toID()) }
    }
}