// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestNotifierTest : RobolectricTest() {
    @Test
    fun `first poll delivers fresh completion and discards stale backlog`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(1, 90_000)).put(message(2, 30)))
        assertEquals(1, shadowOf(manager).size())
        assertEquals(2L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:server/cerro", 0))
        manager.cancelAll()

        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(2, 30)))
        assertEquals(0, shadowOf(manager).size())
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/other", JSONArray().put(message(2, 30)))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `disabled notifications do not consume a fresh inbox entry`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        shadowOf(manager).setNotificationsEnabled(false)
        val inbox = JSONArray().put(message(1, 30))
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        assertEquals(0L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:server/cerro", 0))
        shadowOf(manager).setNotificationsEnabled(true)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `a completion can be answered until it has been`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            "server/hill",
            JSONArray()
                .put(message(1, 30).put("sender", "cerro"))
                .put(message(2, 30).put("sender", "cerro").put("replied", true))
                .put(message(3, 30)),
        )
        val posted = (1..3).map { shadowOf(manager).getNotification(5_140_000 + it) }
        assertEquals(3, shadowOf(manager).size())
        assertEquals(
            listOf("Good job!", "Reply"),
            posted[0].actions.map { it.title.toString() },
            "an unanswered completion offers both a cheer and free text",
        )
        assertNull(posted[1].actions, "an answered completion cannot be answered twice")
        assertNull(posted[2].actions, "a notification without a sender has nobody to answer")

        val cheer = shadowOf(posted[0].actions[0].actionIntent).savedIntent
        assertEquals("Good job!", AnkiquestReply.message(cheer))
        val data = AnkiquestReply.data(cheer, AnkiquestReply.message(cheer))
        assertEquals(1L, data.getLong(AnkiquestReply.NOTIFICATION_KEY, 0))
        assertEquals("Deck complete", data.getString(AnkiquestReply.TITLE_KEY))

        val typed =
            shadowOf(posted[0].actions[1].actionIntent).savedIntent.also {
                RemoteInput.addResultsToIntent(
                    posted[0].actions[1].remoteInputs,
                    it,
                    Bundle().apply { putCharSequence(AnkiquestReply.MESSAGE_KEY, "  proud of you  ") },
                )
            }
        assertEquals("proud of you", AnkiquestReply.message(typed))
    }

    @Test
    fun `a sent reply replaces the buttons and a failed one keeps them`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val data =
            Data
                .Builder()
                .putLong(AnkiquestReply.NOTIFICATION_KEY, 7)
                .putInt(AnkiquestReply.TAG_KEY, 5_140_007)
                .putString(AnkiquestReply.TITLE_KEY, "Deck complete")
                .putString(AnkiquestReply.BODY_KEY, "Cerro has finished Spanish for today.")
                .putString(AnkiquestReply.MESSAGE_KEY, "Good job!")
                .build()

        AnkiquestNotifier.onReplySent(targetContext, data, "Cerro")
        assertEquals(1, shadowOf(manager).size())
        val sent = shadowOf(manager).getNotification(5_140_007)
        assertEquals("Sent to Cerro: Good job!", sent.extras.getString(Notification.EXTRA_TEXT))
        assertNull(sent.actions)

        AnkiquestNotifier.onReplyFailed(targetContext, data)
        val failed = shadowOf(manager).getNotification(5_140_007)
        assertEquals(1, shadowOf(manager).size(), "the same notification is updated in place")
        assertEquals("Could not send \u201cGood job!\u201d. Tap a button to try again.", failed.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("Good job!", "Reply"), failed.actions.map { it.title.toString() })
    }

    @Test
    @SuppressLint("NewApi") // channels require O, guaranteed by @Config
    fun `a nudge buzzes on its own channel while the rest stay quiet`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            "server/cerro",
            JSONArray()
                .put(message(1, 30).put("kind", "completion"))
                .put(message(2, 30).put("kind", "nudge").put("title", "Keep going")),
        )

        assertEquals("ankiquest", shadowOf(manager).getNotification(5_140_001).channelId)
        assertEquals("ankiquestNudges", shadowOf(manager).getNotification(5_140_002).channelId)
        assertTrue(manager.getNotificationChannel("ankiquestNudges").shouldVibrate())
        assertFalse(
            manager.getNotificationChannel("ankiquest").shouldVibrate(),
            "the rest stay as quiet as they were",
        )
    }

    @Test
    @Config(sdk = [24])
    fun `a nudge carries its own buzz where there are no channels`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            "server/cerro",
            JSONArray()
                .put(message(1, 30).put("kind", "completion"))
                .put(message(2, 30).put("kind", "nudge")),
        )

        assertNull(shadowOf(manager).getNotification(5_140_001).vibrate)
        assertEquals(
            listOf(0L, 250L, 150L, 250L),
            shadowOf(manager).getNotification(5_140_002).vibrate?.toList(),
        )
    }

    private fun message(
        id: Long,
        ageSeconds: Long,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("created_at", TimeManager.time.intTimeMS() / 1000 - ageSeconds)
            .put("title", "Deck complete")
            .put("body", "Cerro has finished Spanish for today.")
}
