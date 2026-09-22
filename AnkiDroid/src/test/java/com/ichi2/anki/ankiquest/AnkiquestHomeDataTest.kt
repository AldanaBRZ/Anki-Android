// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiquestHomeDataTest : RobolectricTest() {
    private lateinit var server: HttpServer

    @Volatile private lateinit var account: HomeAccount
    private lateinit var repository: HomeRepository
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val bodies = CopyOnWriteArrayList<String>()

    @Volatile private var activityStatus = 200

    @Volatile private var challengeStatus = 200

    @Volatile private var allStatus = 200

    @Volatile private var changeAccountDuringRequest = false

    @Before
    fun prepare() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        account = HomeAccount("http://127.0.0.1:${server.address.port}", "member name", "secret")
        repository = HomeRepository({ account })
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests += path to exchange.requestHeaders.getFirst("Authorization")
            bodies += exchange.requestBody.bufferedReader().use { it.readText() }
            var status = allStatus
            if (status == 200 && path.startsWith("/api/activity")) status = activityStatus
            if (status == 200 && path.startsWith("/api/community/challenges")) status = challengeStatus
            val response =
                when {
                    path.startsWith("/api/profile") -> "{\"user\":\"member name\",\"level\":2}"
                    path.startsWith("/api/community/challenges") -> "{\"challenges\":[],\"recipients\":[]}"
                    path.startsWith("/api/notifications") -> "[{\"id\":7,\"title\":\"A message\",\"body\":\"Hello\",\"sender\":\"friend\"}]"
                    path.endsWith("/read") -> ""
                    path.startsWith("/api/activity") ->
                        """{"items":[{"id":7,"title":"Goal invitation","body":"Join me","sender":"friend",
                            |"challenge_id":42,"read_at":null}],"unread_count":1,"next_before":7}
                        """.trimMargin()
                    else -> "{}"
                }
            if (changeAccountDuringRequest) account = account.copy(token = "changed")
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(
                if (path.endsWith("/read") &&
                    status == 200
                ) {
                    204
                } else {
                    status
                },
                if (path.endsWith("/read") && status == 200) -1 else bytes.size.toLong(),
            )
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `reads are owner authenticated and user path is encoded`() =
        runBlocking {
            val result = repository.load(account)
            assertTrue(result.profile.live && result.challenges.live && result.inbox.live)
            assertTrue(requests.all { it.second == "Bearer secret" })
            assertTrue(requests.all { it.first.contains("member%20name") })
            assertEquals(
                42L,
                result.inbox.value!!
                    .items
                    .single()
                    .challengeId,
            )
            assertEquals(1, result.inbox.value!!.unreadCount)
        }

    @Test
    fun `old activity route falls back without manufacturing unread count`() =
        runBlocking {
            activityStatus = 404
            val inbox = repository.load(account).inbox.value!!
            assertFalse(inbox.modern)
            assertNull(inbox.unreadCount)
            assertEquals(7L, inbox.items.single().id)
            assertTrue(requests.any { it.first.startsWith("/api/notifications/") })
        }

    @Test
    fun `unsupported challenges preserve profile and inbox`() =
        runBlocking {
            challengeStatus = 404
            val result = repository.load(account)
            assertEquals(HomeFailure.UNSUPPORTED, result.challenges.failure)
            assertTrue(result.profile.live && result.inbox.live)
        }

    @Test
    fun `temporary server failure shows cached data but prohibits live mutations`() =
        runBlocking {
            repository.load(account)
            allStatus = 503
            val result = repository.load(account)
            assertTrue(result.challenges.cached)
            assertFalse(result.challenges.live)
            assertEquals(HomeFailure.SERVER, result.challenges.failure)
            assertEquals(2, result.profile.value!!.getInt("level"))
        }

    @Test
    fun `rejected credentials clear every cached private section`() =
        runBlocking {
            repository.load(account)
            challengeStatus = 401
            val result = repository.load(account)
            assertNull(result.profile.value)
            assertNull(result.challenges.value)
            assertNull(result.inbox.value)
            assertNull(repository.cached(account))
        }

    @Test
    fun `response for changed credentials cannot enter cache`() =
        runBlocking {
            val previous = account
            changeAccountDuringRequest = true
            assertFailsWith<HomeAccountChanged> { repository.load(previous) }
            assertNull(repository.cached(account))
        }

    @Test
    fun `an old captured action cannot use credentials for a new account`() =
        runBlocking {
            val previous = account
            account = account.copy(user = "another")
            assertFailsWith<HomeAccountChanged> { repository.challengeAction(previous, 42, "accept") }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `mark read sends only the chosen id with owner credentials`() =
        runBlocking {
            repository.markRead(account, 7)
            assertEquals("/api/activity/member%20name/read", requests.single().first)
            assertEquals("Bearer secret", requests.single().second)
            assertEquals(7L, JSONObject(bodies.single()).getJSONArray("ids").getLong(0))
        }

    @Test
    fun `pagination preserves owner and exclusive cursor`() =
        runBlocking {
            repository.olderActivity(account, 123)
            assertTrue(requests.single().first.endsWith("days=90&limit=100&before=123"))
        }

    @Test
    fun `scope changes for server member and credential and discards cache`() {
        val cache = HomeCache()
        val snapshot = HomeRemote(account.scope, HomeSection(JSONObject()), HomeSection(), HomeSection(), 1)
        cache.select(account)
        cache.save(snapshot)
        assertEquals(snapshot, cache.select(account))
        listOf(account.copy(server = "https://other.example"), account.copy(user = "other"), account.copy(token = "new-token")).forEach {
            assertNotEquals(account.scope, it.scope)
            assertNull(cache.select(it))
            cache.save(snapshot)
            assertNull(cache.select(it))
        }
        assertNull(cache.select(null))
    }

    @Test
    fun `blank and ambiguous server URLs cannot carry credentials`() {
        fun settings(url: String) = mapOf(Ankiquest.URL_KEY to url, Ankiquest.USER_KEY to "member", Ankiquest.TOKEN_KEY to "secret")
        listOf(
            "",
            "not a url",
            "https://user:password@example.com",
            "https://example.com?other=host",
            "https://example.com#section",
        ).forEach {
            assertNull(HomeAccount.from(settings(it), ""))
        }
        assertEquals("https://example.com/member", HomeAccount.from(settings(" https://example.com/ "), "")!!.notificationAccount)
    }

    @Test
    fun `local deck focus retains native scheduler counts and selected deck`() {
        val selected = HomeDeck(12, "Spanish", 3, 2, 19)
        val other = HomeDeck(13, "Biology", 0, 0, 11)
        assertEquals(24, selected.due)
        assertEquals(selected, HomeLocal(listOf(other, selected), 12).focus)
        assertEquals(other, HomeLocal(listOf(other, selected), 999).focus)
        assertEquals(other, HomeLocal(listOf(selected.copy(new = 0, learning = 0, review = 0), other), 12).focus)
    }

    @Test
    fun `invitations sort above active goals and expired history`() {
        fun goal(
            status: String,
            membership: String,
            end: Long = Long.MAX_VALUE,
        ) = HomeChallenge(
            1,
            "A goal",
            "study_days",
            true,
            "friend",
            end,
            6,
            1,
            status,
            listOf(HomeMember(account.user, "Member", membership, 0)),
        )
        assertEquals(0, goal("active", "invited").priority(account.user))
        assertEquals(1, goal("active", "accepted").priority(account.user))
        assertEquals(2, goal("complete", "accepted").priority(account.user))
        assertEquals(2, goal("ended", "invited", 1).priority(account.user))
        assertEquals(2, goal("active", "left").priority(account.user))
    }

    @Test
    fun `only supported top level routes are accepted`() {
        assertEquals("today", AnkiquestHomeActivity.validTab("unknown"))
        assertEquals("activity", AnkiquestHomeActivity.validTab("activity"))
        assertEquals("friends", AnkiquestHomeActivity.validTab("friends"))
    }
}
