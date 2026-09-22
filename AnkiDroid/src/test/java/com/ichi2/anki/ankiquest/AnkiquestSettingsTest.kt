// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.webkit.WebView
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboMenu
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestSettingsTest : RobolectricTest() {
    @Before
    fun configureAccount() {
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, "https://quest.example/anki")
            putString(Ankiquest.USER_KEY, "cerro")
            putString(Ankiquest.TOKEN_KEY, "saved-test-token")
        }
    }

    @Test
    fun `streak protection is available in native settings`() {
        val keys = mutableListOf<String>()
        targetContext.resources.getXml(R.xml.preferences_ankiquest).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                val key = xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "key", 0)
                if (key != 0) keys.add(targetContext.getString(key))
            }
        }
        assertTrue("ankiquestStreakProtection" in keys, "The settings page must include the leaderboard's streak protection setting")
    }

    @Test
    fun `leaderboard offers the full native settings page`() {
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java).create()
        saveControllerForCleanup(controller)
        val menu = RoboMenu()
        controller.get().onCreateOptionsMenu(menu)
        assertTrue((0 until menu.size()).any { menu.getItem(it).title == targetContext.getString(R.string.settings) })
    }

    @Test
    fun `dashboard receives the saved account without asking for its token again`() {
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java).create()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        webView.webViewClient.onPageFinished(webView, "https://quest.example/anki/#cerro")
        val script = assertNotNull(shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(script.contains("saved-test-token"))
        assertTrue(script.contains("ankiquest-auth"))
        assertFalse(shadowOf(webView).lastLoadedUrl.contains("saved-test-token"))
    }

    @Test
    fun `credentials are limited to the configured origin and known AnkiQuest pages`() {
        val session = AnkiquestWebSession("https://quest.example:443/anki/#cerro", "cerro", "secret")
        for (route in listOf("", "hour", "day", "week", "month", "year", "all", "records", "community")) {
            assertTrue(session.allows("https://quest.example/anki/$route#cerro"), route)
            assertNotNull(session.script("https://quest.example/anki/$route#cerro"))
        }
        for (url in listOf(
            "http://quest.example/anki/",
            "https://quest.example:8443/anki/",
            "https://other.example/anki/",
            "https://quest.example/elsewhere/",
            "https://quest.example/anki/login",
            "https://quest.example/anki/week/extra",
            "https://quest.example/anki/?page=external",
            "https://user:password@quest.example/anki/",
            "javascript:alert(1)",
        )) {
            assertFalse(session.allows(url), url)
            assertNull(session.script(url), url)
        }
        assertFalse(AnkiquestWebSession("https://user:password@quest.example/#cerro", "cerro", "secret").allows("https://quest.example/"))
    }

    @Test
    fun `community calendar links keep the saved account only for a single supported period`() {
        val session = AnkiquestWebSession("https://quest.example/anki/#cerro", "cerro", "secret")
        for (period in listOf("day", "week", "month")) {
            val url = "https://quest.example/anki/community?period=$period#calendar"
            assertTrue(session.allows(url), url)
            assertNotNull(session.script(url))
        }
        for (url in listOf(
            "https://quest.example/anki/community?period=year#calendar",
            "https://quest.example/anki/community?period=#calendar",
            "https://quest.example/anki/community?#calendar",
            "https://quest.example/anki/community?period=day&period=week#calendar",
            "https://quest.example/anki/community?period=day&period=day#calendar",
            "https://quest.example/anki/community?period=day&next=external#calendar",
            "https://quest.example/anki/community?next=external&period=day#calendar",
            "https://quest.example/anki/community?period=day&#calendar",
            "https://quest.example/anki/community?period=day?next=external#calendar",
            "https://quest.example/anki/community?%70eriod=day#calendar",
            "https://quest.example/anki/community?period=%64ay#calendar",
            "https://quest.example/anki/community?page=day#calendar",
            "https://quest.example/anki/week?period=day#calendar",
            "https://quest.example/anki/?period=day#calendar",
            "https://other.example/anki/community?period=day#calendar",
        )) {
            assertFalse(session.allows(url), url)
            assertNull(session.script(url), url)
        }
    }

    @Test
    fun `removing saved credentials clears the page session`() {
        val session = AnkiquestWebSession("https://quest.example/#cerro", "cerro", "")
        assertTrue(assertNotNull(session.script("https://quest.example/")).contains("window.ankiquestSession = null"))
    }

    @Test
    fun `credential handoff safely encodes quotes and unicode`() {
        val user = "cé\"rro"
        val token = "private\"token\nnext line"
        val session = AnkiquestWebSession("https://quest.example:443/#cerro", user, token)
        val script = assertNotNull(session.script("https://quest.example/"))
        val credentials = JSONObject(script.substringAfter("window.ankiquestSession = ").substringBefore(";\n"))
        assertEquals(user, credentials.getString("user"))
        assertEquals(token, credentials.getString("token"))
        assertFalse(script.contains(":443"))
    }

    @Test
    fun `community reminders open with the same saved account`() {
        val intent = Intent(targetContext, AnkiquestActivity::class.java).putExtra(AnkiquestActivity.COMMUNITY_REMINDERS, true)
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java, intent).create()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        assertEquals("https://quest.example/anki/community#reminders", shadowOf(webView).lastLoadedUrl)
        webView.webViewClient.onPageFinished(webView, shadowOf(webView).lastLoadedUrl)
        assertTrue(assertNotNull(shadowOf(webView).lastEvaluatedJavascript).contains("saved-test-token"))
    }

    @Test
    fun `dashboard does not inject credentials after a redirect to another document`() {
        val controller = Robolectric.buildActivity(AnkiquestActivity::class.java).create()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        webView.loadUrl("https://quest.example/anki/login")
        webView.webViewClient.onPageFinished(webView, "https://quest.example/anki/#cerro")
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
    }

    @Test
    fun `returning from native settings reloads the dashboard even for the same account`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val activity = controller.get()
        val menu = RoboMenu()
        activity.onCreateOptionsMenu(menu)
        activity.onOptionsItemSelected(menu.findItem(R.id.ankiquest_settings))
        val webView = activity.findViewById<WebView>(R.id.web_view)
        controller.pause().resume()
        assertEquals(1, shadowOf(webView).reloadInvocations)
    }

    @Test
    fun `changing accounts reloads and clears history after the new page arrives`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        controller.pause()
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.USER_KEY, "hill")
            putString(Ankiquest.TOKEN_KEY, "new-token")
        }
        controller.resume()
        assertEquals("about:blank", shadowOf(webView).lastLoadedUrl)
        webView.webViewClient.onPageFinished(webView, "https://quest.example/anki/#cerro")
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
        webView.webViewClient.onPageFinished(webView, "about:blank")
        assertEquals("https://quest.example/anki/#hill", shadowOf(webView).lastLoadedUrl)
        assertFalse(shadowOf(webView).wasClearHistoryCalled())
        webView.webViewClient.onPageFinished(webView, "https://quest.example/anki/#cerro")
        assertFalse(shadowOf(webView).wasClearHistoryCalled())
        assertNull(shadowOf(webView).lastEvaluatedJavascript)
        webView.webViewClient.onPageFinished(webView, "https://quest.example/anki/#hill")
        assertTrue(shadowOf(webView).wasClearHistoryCalled())
        val script = assertNotNull(shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(script.contains("new-token"))
        assertFalse(script.contains("saved-test-token"))
    }

    @Test
    fun `rotating only the saved token also discards the previous page session`() {
        val controller =
            Robolectric
                .buildActivity(AnkiquestActivity::class.java)
                .create()
                .start()
                .resume()
        saveControllerForCleanup(controller)
        val webView = controller.get().findViewById<WebView>(R.id.web_view)
        controller.pause()
        AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.TOKEN_KEY, "replacement-token") }
        controller.resume()
        assertEquals("about:blank", shadowOf(webView).lastLoadedUrl)
        webView.webViewClient.onPageFinished(webView, "about:blank")
        assertEquals("https://quest.example/anki/#cerro", shadowOf(webView).lastLoadedUrl)
        webView.webViewClient.onPageFinished(webView, shadowOf(webView).lastLoadedUrl)
        val script = assertNotNull(shadowOf(webView).lastEvaluatedJavascript)
        assertTrue(script.contains("replacement-token"))
        assertFalse(script.contains("saved-test-token"))
    }
}
