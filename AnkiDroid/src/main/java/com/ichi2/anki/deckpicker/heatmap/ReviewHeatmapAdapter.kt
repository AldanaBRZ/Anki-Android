// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.deckpicker.heatmap

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate

/** One footer after the deck rows. Its year and selection survive recycling. */
class ReviewHeatmapAdapter(
    private val onDaySelected: (ReviewHeatmapData, LocalDate) -> Unit,
    private val onRetry: () -> Unit,
) : RecyclerView.Adapter<ReviewHeatmapAdapter.Holder>() {
    private var data: ReviewHeatmapData? = null
    private var failed = false
    private val viewState = ReviewHeatmapView.State()

    class Holder(
        val heatmap: ReviewHeatmapView,
    ) : RecyclerView.ViewHolder(heatmap)

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder =
        Holder(
            ReviewHeatmapView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                onDaySelected = this@ReviewHeatmapAdapter.onDaySelected
                onRetry = this@ReviewHeatmapAdapter.onRetry
            },
        )

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val currentData = data
        when {
            currentData != null -> holder.heatmap.setData(currentData, viewState)
            failed -> holder.heatmap.setError()
            else -> holder.heatmap.setLoading()
        }
    }

    fun setData(data: ReviewHeatmapData) {
        this.data = data
        failed = false
        notifyItemChanged(0)
    }

    fun setLoading() {
        data = null
        failed = false
        notifyItemChanged(0)
    }

    fun setError() {
        data = null
        failed = true
        notifyItemChanged(0)
    }
}
