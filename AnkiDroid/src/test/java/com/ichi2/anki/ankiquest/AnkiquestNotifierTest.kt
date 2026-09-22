// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.edit
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
    private fun replyAccount(): HomeAccount {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, "https://server.test")
            putString(Ankiquest.USER_KEY, "hill")
            putString(Ankiquest.TOKEN_KEY, "hill-token")
        }
        return AnkiquestHomeData.account()!!
    }

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
    fun `an established inbox delivers messages missed for more than a day`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(1, 30)))
        manager.cancelAll()
        val missed = message(2, 0)
        collectionTime.addD(2)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(missed))
        assertEquals(1, shadowOf(manager).size())
        assertEquals(2L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:server/cerro", 0))
    }

    @Test
    fun `messages do not expire while waiting for phone notification permission`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val inbox = JSONArray().put(message(1, 30))
        shadowOf(manager).setNotificationsEnabled(false)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        collectionTime.addD(2)
        shadowOf(manager).setNotificationsEnabled(true)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `a successful empty inbox starts the delivery window`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray())
        val missed = message(1, 0)
        collectionTime.addD(2)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(missed))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `an existing installation keeps unseen retained messages after updating`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiDroidApp.sharedPrefs().edit(commit = true) { putLong("ankiquestCompletionCursor:server/cerro", 1L) }
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(2, 90_000)))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `a completion can be answered until it has been`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val account = replyAccount()
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            account.notificationAccount,
            JSONArray()
                .put(message(1, 30).put("sender", "cerro"))
                .put(message(2, 30).put("sender", "cerro").put("replied", true))
                .put(message(3, 30)),
            account.scope,
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
        assertEquals(account.scope, data.getString(AnkiquestReply.SCOPE_KEY))
        assertEquals(account.notificationAccount, data.getString(AnkiquestReply.ACCOUNT_KEY))

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
    fun `suggested replies do not duplicate the quick cheer on incoming or failed notifications`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            "server/hill",
            JSONArray().put(message(1, 30).put("sender", "cerro")),
        )
        val incoming = shadowOf(manager).getNotification(5_140_001)
        val cheer = shadowOf(incoming.actions[0].actionIntent).savedIntent
        AnkiquestNotifier.onReplyFailed(targetContext, AnkiquestReply.data(cheer, AnkiquestReply.message(cheer)))
        val failed = shadowOf(manager).getNotification(5_140_001)

        for (notification in listOf(incoming, failed)) {
            val reply = notification.actions.single { it.remoteInputs?.isNotEmpty() == true }
            val input = reply.remoteInputs.single()
            val choices = input.choices.map { it.toString() }
            val labels = notification.actions.map { it.title.toString() } + choices
            assertEquals(1, labels.count { it == "Good job!" }, "offer the quick cheer only once")
            assertEquals(listOf("Nice one \uD83D\uDD25", "Keep it up!"), choices)
            assertTrue(input.allowFreeFormInput, "custom replies remain available")
            assertFalse(reply.allowGeneratedReplies, "Android must not add another quick cheer suggestion")
        }
    }

    @Test
    fun `reply suggestions are limited to the choices supplied by the app`() {
        val reply = AnkiquestReply.actions(targetContext, 1, 5_140_001, "Deck complete", "Completed Spanish").last()
        assertFalse(reply.allowGeneratedReplies, "Android must not add another quick cheer suggestion")
    }

    @Test
    fun `a sent reply replaces the buttons and a failed one keeps them`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val account = replyAccount()
        val data =
            Data
                .Builder()
                .putLong(AnkiquestReply.NOTIFICATION_KEY, 7)
                .putInt(AnkiquestReply.TAG_KEY, 5_140_007)
                .putString(AnkiquestReply.TITLE_KEY, "Deck complete")
                .putString(AnkiquestReply.BODY_KEY, "Cerro has finished Spanish for today.")
                .putString(AnkiquestReply.MESSAGE_KEY, "Good job!")
                .putString(AnkiquestReply.ACCOUNT_KEY, account.notificationAccount)
                .putString(AnkiquestReply.SCOPE_KEY, account.scope)
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
    @Config(sdk = [24])
    @Suppress("DEPRECATION") // pre-O alerts are configured on the notification itself
    fun `reply acknowledgements stay silent when incoming notifications become alerts`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val account = replyAccount()
        val data =
            Data
                .Builder()
                .putInt(AnkiquestReply.TAG_KEY, 5_140_007)
                .putString(AnkiquestReply.ACCOUNT_KEY, account.notificationAccount)
                .putString(AnkiquestReply.SCOPE_KEY, account.scope)
                .build()
        AnkiquestNotifier.onReplySent(targetContext, data, "Cerro")
        val posted = shadowOf(manager).getNotification(5_140_007)
        assertNull(posted.vibrate)
        assertNull(posted.sound)
        assertEquals(0, posted.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        AnkiquestNotifier.onReplyFailed(targetContext, data)
        val failed = shadowOf(manager).getNotification(5_140_007)
        assertNull(failed.vibrate)
        assertNull(failed.sound)
        assertEquals(0, failed.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
    }

    @Test
    @SuppressLint("NewApi") // channels require O, guaranteed by @Config
    fun `new general and nudge channels vibrate and request heads up alerts`() {
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
        assertTrue(manager.getNotificationChannel("ankiquest").shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("ankiquest").importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("ankiquestNudges").importance)
    }

    @Test
    @Config(sdk = [24])
    @Suppress("DEPRECATION") // Notification.vibrate is how a pre-O phone buzzes
    fun `all incoming alerts request sound buzz and heads up before channels existed`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            "server/cerro",
            JSONArray()
                .put(message(1, 30).put("kind", "completion"))
                .put(message(2, 30).put("kind", "nudge")),
        )

        for (id in 1..2) {
            val notification = shadowOf(manager).getNotification(5_140_000 + id)
            assertEquals(listOf(0L, 250L, 150L, 250L), notification.vibrate?.toList())
            assertEquals(Notification.PRIORITY_HIGH, notification.priority)
            assertTrue(notification.defaults and Notification.DEFAULT_SOUND != 0)
        }
    }

    @Test
    @SuppressLint("NewApi") // channels require O, guaranteed by @Config
    fun `a muted nudge does not block later server messages`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(NotificationChannel("ankiquestNudges", "Nudges", NotificationManager.IMPORTANCE_NONE))
        val inbox = JSONArray().put(message(1, 30).put("kind", "nudge")).put(message(2, 30).put("kind", "message"))
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", inbox)
        assertEquals(1, shadowOf(manager).size())
        assertNull(shadowOf(manager).getNotification(5_140_001))
        assertEquals("Deck complete", shadowOf(manager).getNotification(5_140_002).extras.getString(Notification.EXTRA_TITLE))
        assertEquals(2L, AnkiDroidApp.sharedPrefs().getLong("ankiquestCompletionCursor:server/cerro", 0))
    }

    @Test
    @SuppressLint("NewApi") // channels require O, guaranteed by @Config
    fun `muted general messages do not block a later nudge`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        manager.createNotificationChannel(NotificationChannel("ankiquest", "ankiquest", NotificationManager.IMPORTANCE_NONE))
        AnkiquestNotifier.onDeckCompletions(
            targetContext,
            "server/cerro",
            JSONArray().put(message(1, 30)).put(message(2, 30).put("kind", "nudge")),
        )
        assertEquals(1, shadowOf(manager).size())
        assertEquals("ankiquestNudges", shadowOf(manager).getNotification(5_140_002).channelId)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("ankiquest").importance)
    }

    @Test
    @SuppressLint("NewApi") // channels require O, guaranteed by @Config
    fun `existing quiet channel choices are not replaced or upgraded`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        val channel =
            NotificationChannel("ankiquest", "My quiet alerts", NotificationManager.IMPORTANCE_LOW).apply {
                enableVibration(false)
                setSound(null, null)
            }
        manager.createNotificationChannel(channel)
        AnkiquestNotifier.onDeckCompletions(targetContext, "server/cerro", JSONArray().put(message(1, 30)))
        val saved = manager.getNotificationChannel("ankiquest")
        assertEquals(NotificationManager.IMPORTANCE_LOW, saved.importance)
        assertFalse(saved.shouldVibrate())
        assertNull(saved.sound)
        assertEquals(listOf("ankiquest"), manager.notificationChannels.map { it.id }.filter { it.startsWith("ankiquest") })
    }

    @Test
    fun `a denied streak reminder can be delivered once notifications are enabled`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        AnkiDroidApp.sharedPrefs().edit(commit = true) { putString(AnkiquestNotifier.STREAK_HOURS_KEY, "24") }
        val profile = JSONObject().put("at_risk", true).put("streak", 7)
        shadowOf(manager).setNotificationsEnabled(false)
        AnkiquestNotifier.onProfile(targetContext, profile)
        assertFalse(AnkiDroidApp.sharedPrefs().contains("ankiquestStreakNotifiedDay"))
        shadowOf(manager).setNotificationsEnabled(true)
        AnkiquestNotifier.onProfile(targetContext, profile)
        assertEquals(1, shadowOf(manager).size())
        manager.cancelAll()
        AnkiquestNotifier.onProfile(targetContext, profile)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    @SuppressLint("NewApi") // channels require O, guaranteed by @Config
    fun `alert settings open the selected existing Android channel`() {
        val manager = targetContext.getSystemService<NotificationManager>()!!
        for ((nudge, id) in listOf(false to "ankiquest", true to "ankiquestNudges")) {
            val intent = AnkiquestNotifier.alertSettingsIntent(targetContext, nudge)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(targetContext.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(id, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(id).importance)
            assertTrue(manager.getNotificationChannel(id).shouldVibrate())
        }
    }

    @Test
    @Config(sdk = [24])
    fun `alert settings open app details on phones without channels`() {
        val intent = AnkiquestNotifier.alertSettingsIntent(targetContext, false)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${targetContext.packageName}", intent.data.toString())
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
