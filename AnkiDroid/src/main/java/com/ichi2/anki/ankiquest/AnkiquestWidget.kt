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
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import java.text.NumberFormat

/** Homescreen widget with this week's ankiquest leaderboard. Tapping it opens the dashboard. */
class AnkiquestWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val CACHE_KEY = "ankiquestWidgetLeaderboard"
        private const val MIN_REFRESH_MS = 30 * 1000L
        private const val OWN_ROW_COLOR = 0xFFF0B840.toInt()
        private const val ROW_COLOR = 0xFFE8E6E1.toInt()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val rows =
            intArrayOf(
                R.id.ankiquest_widget_row_0,
                R.id.ankiquest_widget_row_1,
                R.id.ankiquest_widget_row_2,
                R.id.ankiquest_widget_row_3,
                R.id.ankiquest_widget_row_4,
            )
        private val names =
            intArrayOf(
                R.id.ankiquest_widget_name_0,
                R.id.ankiquest_widget_name_1,
                R.id.ankiquest_widget_name_2,
                R.id.ankiquest_widget_name_3,
                R.id.ankiquest_widget_name_4,
            )
        private val levels =
            intArrayOf(
                R.id.ankiquest_widget_level_0,
                R.id.ankiquest_widget_level_1,
                R.id.ankiquest_widget_level_2,
                R.id.ankiquest_widget_level_3,
                R.id.ankiquest_widget_level_4,
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
        private var refreshedAt = 0L

        /** Refreshes placed widgets, at most every [MIN_REFRESH_MS]. */
        fun requestUpdate(context: Context) {
            val now = TimeManager.time.intTimeMS()
            if (now - refreshedAt < MIN_REFRESH_MS || widgetIds(context).isEmpty()) return
            refreshedAt = now
            scope.launch { render(context.applicationContext) }
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager
                .getInstance(context)
                .getAppWidgetIds(ComponentName(context, AnkiquestWidget::class.java))

        private suspend fun render(context: Context) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_ankiquest)
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, AnkiquestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            views.setOnClickPendingIntent(R.id.ankiquest_widget_root, open)

            val prefs = AnkiDroidApp.sharedPrefs()
            if (Ankiquest.dashboardUrl() == null) {
                status(views, context.getString(R.string.ankiquest_widget_unconfigured))
            } else {
                try {
                    val board = Ankiquest.leaderboard()
                    prefs.edit { putString(CACHE_KEY, board.toString()) }
                    fill(context, views, board, offline = false)
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest widget refresh failed")
                    val cached = prefs.getString(CACHE_KEY, null)
                    if (cached == null) {
                        status(views, context.getString(R.string.ankiquest_widget_offline))
                    } else {
                        fill(context, views, JSONArray(cached), offline = true)
                    }
                }
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
            offline: Boolean,
        ) {
            if (offline) {
                views.setTextViewText(R.id.ankiquest_widget_header, context.getString(R.string.ankiquest_widget_title_offline))
            }
            if (board.length() == 0) {
                status(views, context.getString(R.string.ankiquest_widget_empty))
                return
            }
            val me = Ankiquest.player()
            val numbers = NumberFormat.getIntegerInstance()
            for (i in rows.indices) {
                if (i >= board.length()) {
                    views.setViewVisibility(rows[i], View.GONE)
                    continue
                }
                val entry = board.getJSONObject(i)
                views.setViewVisibility(rows[i], View.VISIBLE)
                views.setTextViewText(names[i], "${i + 1}  ${entry.getString("display")}")
                views.setTextColor(names[i], if (entry.getString("user") == me) OWN_ROW_COLOR else ROW_COLOR)
                views.setTextViewText(levels[i], context.getString(R.string.ankiquest_widget_level, entry.getInt("level")))
                views.setTextViewText(xps[i], numbers.format(entry.getLong("week_xp")))
            }
        }
    }
}
