/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import java.text.NumberFormat

/** Homescreen widget with this week's ankiquest leaderboard. Tapping it opens the dashboard. */
class AnkiquestWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        AnkiquestPoll.schedule(context)
        AnkiquestPoll.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        AnkiquestPoll.schedule(context)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_REFRESH) {
            showRefreshing(context)
            AnkiquestPoll.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_REFRESH = "com.ichi2.anki.ankiquest.WIDGET_REFRESH"
        private const val MIN_REFRESH_MS = 30 * 1000L
        private val medals = arrayOf("👑", "🥈", "🥉")

        private val rows =
            intArrayOf(
                R.id.ankiquest_widget_row_0,
                R.id.ankiquest_widget_row_1,
                R.id.ankiquest_widget_row_2,
                R.id.ankiquest_widget_row_3,
                R.id.ankiquest_widget_row_4,
            )
        private val ranks =
            intArrayOf(
                R.id.ankiquest_widget_rank_0,
                R.id.ankiquest_widget_rank_1,
                R.id.ankiquest_widget_rank_2,
                R.id.ankiquest_widget_rank_3,
                R.id.ankiquest_widget_rank_4,
            )
        private val names =
            intArrayOf(
                R.id.ankiquest_widget_name_0,
                R.id.ankiquest_widget_name_1,
                R.id.ankiquest_widget_name_2,
                R.id.ankiquest_widget_name_3,
                R.id.ankiquest_widget_name_4,
            )
        private val streaks =
            intArrayOf(
                R.id.ankiquest_widget_streak_0,
                R.id.ankiquest_widget_streak_1,
                R.id.ankiquest_widget_streak_2,
                R.id.ankiquest_widget_streak_3,
                R.id.ankiquest_widget_streak_4,
            )
        private val levels =
            intArrayOf(
                R.id.ankiquest_widget_level_0,
                R.id.ankiquest_widget_level_1,
                R.id.ankiquest_widget_level_2,
                R.id.ankiquest_widget_level_3,
                R.id.ankiquest_widget_level_4,
            )
        private val bars =
            intArrayOf(
                R.id.ankiquest_widget_bar_0,
                R.id.ankiquest_widget_bar_1,
                R.id.ankiquest_widget_bar_2,
                R.id.ankiquest_widget_bar_3,
                R.id.ankiquest_widget_bar_4,
            )
        private val xps =
            intArrayOf(
                R.id.ankiquest_widget_xp_0,
                R.id.ankiquest_widget_xp_1,
                R.id.ankiquest_widget_xp_2,
                R.id.ankiquest_widget_xp_3,
                R.id.ankiquest_widget_xp_4,
            )

        @Volatile
        private var requestedAt = 0L

        /** Refreshes the leaderboard soon, at most every [MIN_REFRESH_MS]. */
        fun requestUpdate(context: Context) {
            val now = TimeManager.time.intTimeMS()
            if (now - requestedAt < MIN_REFRESH_MS) return
            requestedAt = now
            AnkiquestPoll.refreshNow(context)
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager
                .getInstance(context)
                .getAppWidgetIds(ComponentName(context, AnkiquestWidget::class.java))

        private fun showRefreshing(context: Context) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_ankiquest)
            views.setTextViewText(R.id.ankiquest_widget_updated, "…")
            AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(ids, views)
        }

        /**
         * Draws [board] into every placed widget.
         *
         * @param board this week's standings, or null when ankiquest is not configured
         * @param fetchedAt when [board] was fetched from the server
         * @param offline whether the last fetch failed and [board] is a cached copy
         */
        fun render(
            context: Context,
            board: JSONArray?,
            fetchedAt: Long,
            offline: Boolean,
        ) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_ankiquest)
            views.setOnClickPendingIntent(
                R.id.ankiquest_widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, AnkiquestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.ankiquest_widget_refresh,
                PendingIntent.getBroadcast(
                    context,
                    1,
                    Intent(context, AnkiquestWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            val time =
                if (fetchedAt > 0) DateUtils.formatDateTime(context, fetchedAt, DateUtils.FORMAT_SHOW_TIME) else ""
            views.setTextViewText(
                R.id.ankiquest_widget_updated,
                if (offline) context.getString(R.string.ankiquest_widget_offline_at, time) else time,
            )

            when {
                board == null -> status(views, context.getString(R.string.ankiquest_widget_unconfigured))
                board.length() == 0 && offline -> status(views, context.getString(R.string.ankiquest_widget_offline))
                board.length() == 0 -> status(views, context.getString(R.string.ankiquest_widget_empty))
                else -> fill(context, views, board)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        private fun status(
            views: RemoteViews,
            text: String,
        ) {
            views.setTextViewText(R.id.ankiquest_widget_status, text)
            views.setViewVisibility(R.id.ankiquest_widget_status, View.VISIBLE)
        }

        private fun fill(
            context: Context,
            views: RemoteViews,
            board: JSONArray,
        ) {
            val me = Ankiquest.player()
            val numbers = NumberFormat.getIntegerInstance()
            val leaderXp = board.getJSONObject(0).getLong("week_xp").coerceAtLeast(1)
            for (i in rows.indices) {
                if (i >= board.length()) {
                    views.setViewVisibility(rows[i], View.GONE)
                    continue
                }
                val entry = board.getJSONObject(i)
                val weekXp = entry.getLong("week_xp")
                val streak = entry.optInt("streak")
                views.setViewVisibility(rows[i], View.VISIBLE)
                views.setInt(
                    rows[i],
                    "setBackgroundResource",
                    if (entry.getString("user") == me) R.drawable.ankiquest_widget_row_self else 0,
                )
                views.setTextViewText(ranks[i], medals.getOrNull(i) ?: "${i + 1}")
                views.setTextViewText(names[i], entry.getString("display"))
                views.setTextViewText(streaks[i], if (streak > 0) "🔥$streak" else "")
                views.setTextViewText(levels[i], context.getString(R.string.ankiquest_widget_level, entry.getInt("level")))
                views.setProgressBar(bars[i], 1000, (weekXp * 1000 / leaderXp).toInt(), false)
                views.setTextViewText(xps[i], numbers.format(weekXp))
            }
        }
    }
}
