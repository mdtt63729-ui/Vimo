package com.atube.app.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atube.app.api.obj.StreamItem
import com.atube.app.extensions.toID
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.recommendation.shorts.ShortsCandidateCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Shorts feed.
 *
 * Uses [ShortsCandidateCoordinator] for multi-source continuous loading.
 * Deduplicates Shorts by video ID and never shows the same Short twice
 * in the same session.
 */
class ShortsViewModel : ViewModel() {

    val shorts: MutableLiveData<List<StreamItem>> = MutableLiveData(emptyList())
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(false)
    val error: MutableLiveData<String?> = MutableLiveData(null)

    private var loadJob: Job? = null
    private val shortsCoordinator = ShortsCandidateCoordinator()
    private val seenIds = mutableSetOf<String>()

    /**
     * Load initial Shorts batch.
     * Fetches from multiple sources (trending + search) with deduplication.
     */
    fun loadShorts(context: Context) {
        if (isLoading.value == true) return
        isLoading.value = true
        error.value = null

        // Reset for fresh load
        shortsCoordinator.reset()
        seenIds.clear()

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val region = PreferenceHelper.getTrendingRegion(context)
                val batch = withContext(Dispatchers.IO) {
                    shortsCoordinator.fetchCombinedBatch(region)
                }

                // Deduplicate
                val unique = batch.filter { stream ->
                    val id = stream.url?.toID() ?: return@filter false
                    if (id in seenIds) return@filter false
                    seenIds.add(id)
                    true
                }

                shorts.value = unique
                isLoading.value = false
            } catch (e: Exception) {
                error.value = e.message ?: "Failed to load shorts"
                isLoading.value = false
            }
        }
    }

    /**
     * Load more Shorts for continuous feed.
     * Called when user approaches the end of the current list.
     */
    fun loadMoreShorts() {
        if (isLoading.value == true) return
        if (!shortsCoordinator.hasMore()) return

        isLoading.value = true
        loadJob = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    shortsCoordinator.fetchNextBatch()
                }

                // Deduplicate
                val unique = batch.filter { stream ->
                    val id = stream.url?.toID() ?: return@filter false
                    if (id in seenIds) return@filter false
                    seenIds.add(id)
                    true
                }

                val current = shorts.value ?: emptyList()
                if (unique.isNotEmpty()) {
                    shorts.value = current + unique
                }
                isLoading.value = false
            } catch (e: Exception) {
                error.value = e.message ?: "Failed to load more shorts"
                isLoading.value = false
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        shorts.value = emptyList()
        shortsCoordinator.reset()
        seenIds.clear()
    }
}
