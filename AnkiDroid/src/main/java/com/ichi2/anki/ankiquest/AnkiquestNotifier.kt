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

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Data
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.TimeZone
import kotlin.math.ceil

/** Leaderboard position and streak notifications. */
object AnkiquestNotifier {
    const val RANK_KEY = "ankiquestNotifyRank"
    const val STREAK_HOURS_KEY = "ankiquestStreakReminderHours"
    const val ROLLOVER_KEY = "ankiquestRolloverHour"
    const val DEFAULT_STREAK_HOURS = "2"

    private const val CHANNEL = "ankiquest"

    /** Its own channel because it vibrates, and a channel cannot be changed once made. */
    private const val NUDGE_CHANNEL = "ankiquestNudges"
    private val BUZZ = longArrayOf(0, 250, 150, 250)
    private const val ORDER_KEY = "ankiquestLastOrder"
    private const val STREAK_DAY_KEY = "ankiquestStreakNotifiedDay"
    private const val RANK_ID = 5_130_001
    private const val STREAK_ID = 5_130_002
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    @Synchronized
    fun onDeckCompletions(
        context: Context,
        account: String,
        notifications: JSONArray,
    ) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val key = "ankiquestCompletionCursor:$account"
        val entries = (0 until notifications.length()).map { notifications.getJSONObject(it) }.sortedBy { it.getLong("id") }
        var previous = prefs.getLong(key, 0L)
        val now = TimeManager.time.intTimeMS() / 1000
        for (entry in entries) {
            val id = entry.getLong("id")
            if (id <= previous) continue
            // Show recent completions even on the first poll, but never replay an old backlog.
            val fresh = AnkiquestCompletionPolicy.freshNotification(entry.optLong("created_at"), now)
            val tag = 5_140_000 + (id % 1_000_000).toInt()
            val title = entry.getString("title")
            val body = entry.getString("body")
            val answerable = entry.optString("sender").isNotEmpty() && !entry.optBoolean("replied")
            if (fresh &&
                !notify(
                    context,
                    tag,
                    title,
                    body,
                    dashboardIntent(context),
                    if (answerable) AnkiquestReply.actions(context, id, tag, title, body) else emptyList(),
                    entry.optString("kind") == "nudge",
                )
            ) {
                return
            }
            previous = id
            prefs.edit { putLong(key, previous) }
        }
    }

    fun onLeaderboard(
        context: Context,
        board: JSONArray,
    ) {
        val me = Ankiquest.player() ?: return
        val prefs = AnkiDroidApp.sharedPrefs()
        val entries = (0 until board.length()).map { board.getJSONObject(it) }
        val order = entries.map { it.getString("user") }
        val previous = prefs.getString(ORDER_KEY, null)?.split(',')
        prefs.edit { putString(ORDER_KEY, order.joinToString(",")) }

        val rank = order.indexOf(me)
        val before = previous?.indexOf(me) ?: -1
        if (rank < 0 || before < 0 || rank == before) return
        if (!prefs.getBoolean(RANK_KEY, true)) return
        if (entries[rank].getLong("week_xp") == 0L) return

        val numbers = NumberFormat.getIntegerInstance()
        val display = entries.associate { it.getString("user") to it.getString("display") }
        val names = { users: List<String> -> users.joinToString(", ") { display[it] ?: it } }
        val gap =
            if (rank > 0) {
                val ahead = entries[rank - 1]
                " ${numbers.format(ahead.getLong("week_xp") - entries[rank].getLong("week_xp"))} XP behind ${ahead.getString("display")}."
            } else {
                ""
            }
        val (title, body) =
            if (rank < before) {
                val passed = previous!!.subList(0, before).filter { order.indexOf(it) > rank }
                val title = if (rank == 0) "👑 You took the crown" else "▲ You're now #${rank + 1}"
                title to (if (passed.isEmpty()) "" else "You passed ${names(passed)}.") + gap
            } else {
                val overtakers = order.subList(0, rank).filter { (previous!!.indexOf(it)) > before }
                val who = names(overtakers).ifEmpty { "Someone" }
                val title = if (before == 0) "👑 $who took the crown" else "▼ $who passed you"
                title to "You're now #${rank + 1}.$gap"
            }
        notify(context, RANK_ID, title, body.trim(), dashboardIntent(context))
    }

    fun onProfile(
        context: Context,
        profile: JSONObject,
    ) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val hours = prefs.getString(STREAK_HOURS_KEY, DEFAULT_STREAK_HOURS)?.toIntOrNull() ?: 0
        if (hours <= 0 || !profile.optBoolean("at_risk")) return

        val now = TimeManager.time.intTimeMS()
        val local = now + TimeZone.getDefault().getOffset(now)
        val sinceRollover = local - prefs.getInt(ROLLOVER_KEY, 4) * HOUR_MS
        val remaining = DAY_MS - sinceRollover.mod(DAY_MS)
        if (remaining > hours * HOUR_MS) return
        val day = sinceRollover.floorDiv(DAY_MS)
        if (prefs.getLong(STREAK_DAY_KEY, Long.MIN_VALUE) == day) return
        prefs.edit { putLong(STREAK_DAY_KEY, day) }

        val streak = profile.optInt("streak")
        val left = ceil(remaining.toDouble() / HOUR_MS).toInt()
        val freezes = profile.optInt("freezes")
        val body =
            if (freezes > 0) {
                "Review a few cards to keep it. A freeze would cover you, but why spend it?"
            } else {
                "Review a few cards to keep it. No freezes left."
            }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        notify(context, STREAK_ID, "🔥 Your $streak day streak ends in ${left}h", body, open)
    }

    /** Replaces the answered notification with what was said, so the reply is visibly gone. */
    fun onReplySent(
        context: Context,
        data: Data,
        who: String,
    ) {
        val message = data.getString(AnkiquestReply.MESSAGE_KEY).orEmpty()
        notify(
            context,
            data.getInt(AnkiquestReply.TAG_KEY, 0),
            data.getString(AnkiquestReply.TITLE_KEY).orEmpty(),
            context.getString(R.string.ankiquest_reply_sent, who, message),
            dashboardIntent(context),
        )
    }

    /** Keeps the buttons so a reply that never left can be sent again. */
    fun onReplyFailed(
        context: Context,
        data: Data,
    ) {
        val tag = data.getInt(AnkiquestReply.TAG_KEY, 0)
        val title = data.getString(AnkiquestReply.TITLE_KEY).orEmpty()
        val body = data.getString(AnkiquestReply.BODY_KEY).orEmpty()
        val message = data.getString(AnkiquestReply.MESSAGE_KEY).orEmpty()
        notify(
            context,
            tag,
            title,
            context.getString(R.string.ankiquest_reply_failed, message),
            dashboardIntent(context),
            AnkiquestReply.actions(context, data.getLong(AnkiquestReply.NOTIFICATION_KEY, 0), tag, title, body),
        )
    }

    private fun dashboardIntent(context: Context): Intent =
        Intent(context, AnkiquestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        id: Int,
        title: String,
        body: String,
        open: Intent,
        actions: List<NotificationCompat.Action> = emptyList(),
        buzz: Boolean = false,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = if (buzz) NUDGE_CHANNEL else CHANNEL
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(channel, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(
                    context.getString(
                        if (buzz) R.string.ankiquest_nudges_title else R.string.ankiquest_screen_title,
                    ),
                ).setVibrationEnabled(buzz)
                .setVibrationPattern(if (buzz) BUZZ else null)
                .build(),
        )
        if (manager.getNotificationChannel(channel)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) return false
        val notification =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_star_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                // Android 7 and older have no channels; the notification itself buzzes.
                .setVibrate(if (buzz) BUZZ else null)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        id,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).apply { actions.forEach { addAction(it) } }
                .build()
        manager.notify(id, notification)
        return true
    }
}
