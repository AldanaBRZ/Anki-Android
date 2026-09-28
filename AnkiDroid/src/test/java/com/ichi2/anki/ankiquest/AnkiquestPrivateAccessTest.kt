// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestPrivateAccessTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = CopyOnWriteArrayList<ReceivedRequest>()
    private val sessionCookie = "ankiquest_session=opaque-session; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800"

    @Volatile
    private var sessionStatus = 204

    @Volatile
    private var responseCookie: String? = sessionCookie

    private data class ReceivedRequest(
        val path: String,
        val method: String,
        val authorization: String?,
        val body: String,
    )

    @Before
    fun startServer() {
        AnkiDroidApp.sharedPreferencesTestingOverride = targetContext.sharedPrefs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests.add(
                ReceivedRequest(
                    path,
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.bufferedReader().use { it.readText() },
                ),
            )
            if (path == "/auth/session") {
                responseCookie?.let { exchange.responseHeaders.add("Set-Cookie", it) }
                if (sessionStatus == 302) exchange.responseHeaders.add("Location", "$url/redirected")
                exchange.sendResponseHeaders(sessionStatus, -1)
            } else {
                val body = (if (path == "/api/leaderboard") "[]" else "{}").toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
        AnkiDroidApp.sharedPrefs().edit {
            putString(Ankiquest.URL_KEY, url)
            putString(Ankiquest.USER_KEY, "member name")
            putString(Ankiquest.TOKEN_KEY, " member-secret ")
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
        AnkiDroidApp.sharedPreferencesTestingOverride = null
    }

    @Test
    fun `private leaderboard and profile reads send the member token`() =
        runBlocking {
            Ankiquest.leaderboard()
            Ankiquest.profile()

            assertEquals(listOf("/api/leaderboard", "/api/profile/member%20name"), requests.map { it.path })
            assertTrue(requests.all { it.authorization == "Bearer member-secret" })
            assertTrue(requests.all { it.method == "GET" && it.body.isEmpty() })
        }

    @Test
    fun `public server reads still work without a configured token`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.TOKEN_KEY) }

            Ankiquest.leaderboard()
            Ankiquest.profile()

            assertEquals(2, requests.size)
            assertTrue(requests.all { it.authorization == null })
        }

    private fun switchServerDuringSettingsRead() {
        val preferences = targetContext.sharedPrefs()
        val changingPreferences = mockk<SharedPreferences>()

        fun switchServer() {
            preferences.edit {
                putString(Ankiquest.URL_KEY, "https://private.example.test")
                putString(Ankiquest.TOKEN_KEY, "new-private-secret")
            }
        }
        every { changingPreferences.getString(any(), any()) } answers { preferences.getString(firstArg(), secondArg()) }
        every { changingPreferences.getString(Ankiquest.URL_KEY, "") } answers {
            val captured = preferences.getString(Ankiquest.URL_KEY, "")
            switchServer()
            captured
        }
        every { changingPreferences.all } answers {
            val captured = preferences.all
            switchServer()
            captured
        }
        AnkiDroidApp.sharedPreferencesTestingOverride = changingPreferences
    }

    @Test
    fun `account changes never send new server credentials to the captured server`() =
        runBlocking {
            switchServerDuringSettingsRead()

            Ankiquest.profile()

            assertEquals("/api/profile/member%20name", requests.single().path)
            assertEquals("Bearer member-secret", requests.single().authorization)
        }

    @Test
    fun `tokenless settings checks remain anonymous after account changes`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.TOKEN_KEY) }
            switchServerDuringSettingsRead()

            Ankiquest.runFromSettings(targetContext, uploadAll = false)

            assertEquals("/api/profile/member%20name", requests.single().path)
            assertNull(requests.single().authorization)
        }

    @Test
    fun `browser session exchanges bearer only in the request header`() =
        runBlocking {
            val dashboard = Ankiquest.dashboardUrl()!!

            assertEquals(sessionCookie, Ankiquest.dashboardSession(dashboard))
            assertEquals(ReceivedRequest("/auth/session", "POST", "Bearer member-secret", ""), requests.single())
            assertEquals("$url/#member%20name", dashboard)
            assertFalse(dashboard.contains("member-secret"))
        }

    @Test
    fun `browser session skips an unconfigured token`() =
        runBlocking {
            AnkiDroidApp.sharedPrefs().edit { remove(Ankiquest.TOKEN_KEY) }

            assertNull(Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!))
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `browser session never sends credentials for a stale dashboard`() =
        runBlocking {
            val dashboard = Ankiquest.dashboardUrl()!!
            AnkiDroidApp.sharedPrefs().edit { putString(Ankiquest.URL_KEY, "https://new.example.test") }

            assertNull(Ankiquest.dashboardSession(dashboard))
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `older servers without a session endpoint remain supported`() =
        runBlocking {
            sessionStatus = 404

            assertNull(Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!))
            assertEquals(1, requests.size)
        }

    @Test
    fun `invalid member token preserves unauthorized status`() =
        runBlocking {
            sessionStatus = 401

            val error = assertFailsWith<Ankiquest.HttpStatusException> { Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!) }

            assertEquals(401, error.code)
        }

    @Test
    fun `session exchange refuses redirects`() =
        runBlocking {
            sessionStatus = 302

            val error = assertFailsWith<Ankiquest.HttpStatusException> { Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!) }

            assertEquals(302, error.code)
            assertEquals(listOf("/auth/session"), requests.map { it.path })
        }

    @Test
    fun `browser accepts only its host scoped HttpOnly session cookie`() =
        runBlocking {
            for (cookie in listOf(
                null,
                "other=value; Path=/; HttpOnly",
                "ankiquest_session=value; Path=/",
                "ankiquest_session=value; Path=/; HttpOnly; Domain=127.0.0.1",
                "ankiquest_session=value; Path=/other; HttpOnly",
            )) {
                responseCookie = cookie
                assertFailsWith<IOException> { Ankiquest.dashboardSession(Ankiquest.dashboardUrl()!!) }
            }
        }
}
