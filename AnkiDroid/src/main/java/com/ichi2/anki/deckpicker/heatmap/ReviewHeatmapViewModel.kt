// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.deckpicker.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Keeps review history out of the deck-list loading path and survives activity recreation. */
class ReviewHeatmapViewModel(
    private val load: suspend () -> ReviewHeatmapData = { withCol { ReviewHeatmap.load(this) } },
) : ViewModel() {
    sealed interface State {
        data object Loading : State

        data class Loaded(
            val data: ReviewHeatmapData,
        ) : State

        data object Error : State
    }

    private val mutableState = MutableStateFlow<State>(State.Loading)
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh(): Job {
        refreshJob?.cancel()
        return viewModelScope
            .launch {
                if (mutableState.value !is State.Loaded) mutableState.value = State.Loading
                try {
                    val data = load()
                    ensureActive()
                    mutableState.value = State.Loaded(data)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    Timber.w(error, "Unable to load review heatmap")
                    mutableState.value = State.Error
                }
            }.also { refreshJob = it }
    }
}
