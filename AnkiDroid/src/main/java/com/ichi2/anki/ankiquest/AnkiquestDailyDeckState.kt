// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.text.format.DateFormat
import com.ichi2.anki.R
import java.util.Date

enum class DailyDeckStatus { REVIEW_NOW, LEARNING_LATER, DONE, NOTHING_SCHEDULED }

/** Daily remaining includes learning beyond Anki's short learn-ahead window. */
data class DailyDeckState(
    val ready: Long,
    val remaining: Long,
    val reviewed: Long,
    val nextLearningAt: Long? = null,
) {
    val status: DailyDeckStatus get() =
        when {
            ready > 0 -> DailyDeckStatus.REVIEW_NOW
            remaining > 0 && nextLearningAt != null -> DailyDeckStatus.LEARNING_LATER
            remaining > 0 -> DailyDeckStatus.REVIEW_NOW
            reviewed > 0 -> DailyDeckStatus.DONE
            else -> DailyDeckStatus.NOTHING_SCHEDULED
        }

    val readyCount: Long get() = ready.coerceAtLeast(remaining.takeIf { nextLearningAt == null } ?: 0)

    fun label(context: Context): String =
        when (status) {
            DailyDeckStatus.REVIEW_NOW -> context.resources.getQuantityString(R.plurals.aq_deck_ready, readyCount.toInt(), readyCount)
            DailyDeckStatus.LEARNING_LATER ->
                context.getString(
                    R.string.aq_deck_later,
                    DateFormat.getTimeFormat(context).format(Date(requireNotNull(nextLearningAt))),
                )
            DailyDeckStatus.DONE -> context.getString(R.string.aq_deck_done)
            DailyDeckStatus.NOTHING_SCHEDULED -> context.getString(R.string.aq_deck_nothing)
        }

    val color: Int get() =
        when (status) {
            DailyDeckStatus.LEARNING_LATER -> R.color.aq_deck_amber
            DailyDeckStatus.DONE -> R.color.aq_deck_green
            else -> R.color.aq_home_muted
        }

    val icon: Int get() =
        when (status) {
            DailyDeckStatus.LEARNING_LATER -> R.drawable.ic_running_clock
            DailyDeckStatus.DONE -> R.drawable.ic_done
            else -> 0
        }
}
