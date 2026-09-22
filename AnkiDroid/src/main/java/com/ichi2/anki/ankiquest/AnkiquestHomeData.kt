// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A captured identity: queued work must never borrow credentials from changed preferences. */
internal data class HomeAccount(
    val server: String,
    val user: String,
    val token: String,
) {
    val encodedUser: String = URLEncoder.encode(user, "UTF-8").replace("+", "%20")
    val notificationAccount: String = "$server/$encodedUser"
    val scope: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$server\u0000$user\u0000$token".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun url(path: String): HttpUrl = requireNotNull("$server/$path".toHttpUrlOrNull())

    override fun toString(): String = "HomeAccount(server=$server, user=$user)"

    companion object {
        fun from(
            settings: Map<String, *>,
            fallbackUser: String,
        ): HomeAccount? {
            val server = (settings[Ankiquest.URL_KEY] as? String).orEmpty().trim().trimEnd('/')
            val user = (settings[Ankiquest.USER_KEY] as? String).orEmpty().trim().ifEmpty { fallbackUser.trim() }
            val token = (settings[Ankiquest.TOKEN_KEY] as? String).orEmpty().trim()
            val parsed = server.toHttpUrlOrNull() ?: return null
            if (user.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null ||
                parsed.fragment != null
            ) {
                return null
            }
            return HomeAccount(server, user, token)
        }
    }
}

internal data class HomeDeck(
    val id: Long,
    val name: String,
    val new: Int,
    val learning: Int,
    val review: Int,
) {
    val due: Int get() = new + learning + review
}

internal data class HomeLocal(
    val decks: List<HomeDeck>,
    val selected: Long,
) {
    val focus: HomeDeck? get() =
        decks.firstOrNull { it.id == selected && it.due > 0 } ?: decks.firstOrNull { it.due > 0 }
            ?: decks.firstOrNull { it.id == selected }
            ?: decks.firstOrNull()
}

internal data class HomeMember(
    val user: String,
    val display: String,
    val status: String,
    val progress: Long,
)

internal data class HomeChallenge(
    val id: Long,
    val title: String,
    val kind: String,
    val cooperative: Boolean,
    val creator: String,
    val endAt: Long,
    val target: Long,
    val progress: Long,
    val status: String,
    val members: List<HomeMember>,
) {
    fun membership(user: String): String? = members.firstOrNull { it.user == user }?.status

    fun open(now: Long = System.currentTimeMillis()): Boolean = endAt > now && status !in setOf("cancelled", "ended")

    fun priority(user: String): Int =
        when {
            open() && membership(user) == "invited" -> 0
            open() && status != "complete" && membership(user) == "accepted" -> 1
            else -> 2
        }

    companion object {
        fun parse(json: JSONObject): HomeChallenge =
            HomeChallenge(
                json.getLong("id"),
                json.getString("title"),
                json.getString("kind"),
                json.getBoolean("cooperative"),
                json.getString("creator"),
                json.getLong("end_at"),
                json.getLong("target"),
                json.getLong("progress"),
                json.getString("status"),
                json.getJSONArray("members").objects().map {
                    HomeMember(it.getString("user"), it.getString("display"), it.getString("status"), it.getLong("progress"))
                },
            )
    }
}

internal data class HomeRecipient(
    val user: String,
    val display: String,
)

internal data class HomeChallenges(
    val items: List<HomeChallenge>,
    val recipients: List<HomeRecipient>,
) {
    companion object {
        fun parse(json: JSONObject): HomeChallenges =
            HomeChallenges(
                json.getJSONArray("challenges").objects().map(HomeChallenge::parse),
                json.getJSONArray("recipients").objects().map { HomeRecipient(it.getString("user"), it.getString("display")) },
            )
    }
}

internal data class HomeNotice(
    val id: Long,
    val title: String,
    val body: String,
    val sender: String,
    val replied: Boolean,
    val createdAt: Long,
    val challengeId: Long?,
    val unread: Boolean,
) {
    companion object {
        fun parse(json: JSONObject): HomeNotice =
            HomeNotice(
                json.getLong("id"),
                json.getString("title"),
                json.getString("body"),
                json.optString("sender"),
                json.optBoolean("replied"),
                json.optLong("created_at"),
                json.optLong("challenge_id").takeIf { it > 0 },
                json.has("read_at") && json.isNull("read_at"),
            )
    }
}

internal data class HomeInbox(
    val items: List<HomeNotice>,
    val unreadCount: Int?,
    val nextBefore: Long?,
    val modern: Boolean,
) {
    companion object {
        fun parse(json: JSONObject): HomeInbox =
            HomeInbox(
                json.getJSONArray("items").objects().map(HomeNotice::parse),
                json.optInt("unread_count"),
                json.optLong("next_before").takeIf { it > 0 },
                true,
            )

        fun legacy(json: JSONArray): HomeInbox = HomeInbox(json.objects().map(HomeNotice::parse), null, null, false)
    }
}

internal enum class HomeFailure { OFFLINE, AUTH, UNSUPPORTED, SERVER }

internal data class HomeSection<T>(
    val value: T? = null,
    val failure: HomeFailure? = null,
    val cached: Boolean = false,
) {
    val live: Boolean get() = value != null && failure == null
}

internal data class HomeRemote(
    val scope: String,
    val profile: HomeSection<JSONObject>,
    val challenges: HomeSection<HomeChallenges>,
    val inbox: HomeSection<HomeInbox>,
    val fetchedAt: Long,
)

/** Only one identity is retained, in memory. Nothing private is written to disk or backups. */
internal class HomeCache {
    private var scope: String? = null
    private var snapshot: HomeRemote? = null

    @Synchronized
    fun select(account: HomeAccount?): HomeRemote? {
        if (scope != account?.scope) {
            snapshot = null
            scope = account?.scope
        }
        return snapshot
    }

    @Synchronized
    fun save(value: HomeRemote) {
        if (scope == value.scope) snapshot = value
    }

    @Synchronized
    fun clear() {
        scope = null
        snapshot = null
    }
}

internal class HomeHttpException(
    val code: Int,
    val reason: String? = null,
) : IOException("HTTP $code")

internal class HomeAccountChanged : IOException("The AnkiQuest account changed")

internal class HomeRepository(
    private val accountProvider: () -> HomeAccount?,
    private val cache: HomeCache = HomeCache(),
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
) {
    private val json = "application/json".toMediaType()

    fun cached(account: HomeAccount?): HomeRemote? = cache.select(account)

    fun invalidate() = cache.clear()

    private fun ensureCurrent(account: HomeAccount) {
        if (accountProvider()?.scope != account.scope) {
            cache.select(accountProvider())
            throw HomeAccountChanged()
        }
    }

    private fun request(
        account: HomeAccount,
        path: String,
        body: JSONObject? = null,
    ): String {
        ensureCurrent(account)
        val request =
            Request
                .Builder()
                .url(account.url(path))
                .apply {
                    if (account.token.isNotEmpty()) header("Authorization", "Bearer ${account.token}")
                    if (body != null) post(body.toString().toRequestBody(json))
                }.build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string()
            ensureCurrent(account)
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) cache.clear()
                val reason = runCatching { JSONObject(text).optString("error").takeIf { it.isNotBlank() } }.getOrNull()
                throw HomeHttpException(response.code, reason)
            }
            text
        }
    }

    private suspend fun <T> section(
        old: HomeSection<T>?,
        block: () -> T,
    ): HomeSection<T> =
        withContext(Dispatchers.IO) {
            try {
                HomeSection(block())
            } catch (e: HomeAccountChanged) {
                throw e
            } catch (e: HomeHttpException) {
                when (e.code) {
                    401, 403 -> HomeSection(failure = HomeFailure.AUTH)
                    404 -> HomeSection(failure = HomeFailure.UNSUPPORTED)
                    else -> HomeSection(old?.value, HomeFailure.SERVER, old?.value != null)
                }
            } catch (_: IOException) {
                HomeSection(old?.value, HomeFailure.OFFLINE, old?.value != null)
            } catch (_: org.json.JSONException) {
                HomeSection(old?.value, HomeFailure.SERVER, old?.value != null)
            }
        }

    suspend fun load(account: HomeAccount): HomeRemote =
        coroutineScope {
            ensureCurrent(account)
            val old = cache.select(account)
            val profile = async { section(old?.profile) { JSONObject(request(account, "api/profile/${account.encodedUser}")) } }
            val challenges =
                async<HomeSection<HomeChallenges>> {
                    if (account.token.isEmpty()) {
                        HomeSection(failure = HomeFailure.AUTH)
                    } else {
                        section(
                            old?.challenges,
                        ) { HomeChallenges.parse(JSONObject(request(account, "api/community/challenges/${account.encodedUser}"))) }
                    }
                }
            val inbox =
                async<HomeSection<HomeInbox>> {
                    if (account.token.isEmpty()) HomeSection(failure = HomeFailure.AUTH) else section(old?.inbox) { inbox(account) }
                }
            val result = HomeRemote(account.scope, profile.await(), challenges.await(), inbox.await(), System.currentTimeMillis())
            ensureCurrent(account)
            val invalidToken =
                account.token.isNotEmpty() &&
                    listOf(result.profile.failure, result.challenges.failure, result.inbox.failure).contains(HomeFailure.AUTH)
            if (invalidToken) {
                cache.clear()
                result.copy(
                    profile = HomeSection(failure = HomeFailure.AUTH),
                    challenges = HomeSection(failure = HomeFailure.AUTH),
                    inbox = HomeSection(failure = HomeFailure.AUTH),
                )
            } else {
                cache.save(result)
                result
            }
        }

    private fun inbox(
        account: HomeAccount,
        before: Long? = null,
    ): HomeInbox {
        try {
            val suffix = if (before == null) "" else "&before=$before"
            return HomeInbox.parse(JSONObject(request(account, "api/activity/${account.encodedUser}?days=90&limit=100$suffix")))
        } catch (e: HomeHttpException) {
            if (e.code != 404 || before != null) throw e
            return HomeInbox.legacy(JSONArray(request(account, "api/notifications/${account.encodedUser}")))
        }
    }

    suspend fun olderActivity(
        account: HomeAccount,
        before: Long,
    ): HomeInbox = withContext(Dispatchers.IO) { inbox(account, before) }

    suspend fun challengeAction(
        account: HomeAccount,
        id: Long,
        action: String,
    ): HomeChallenges =
        withContext(Dispatchers.IO) {
            require(action in setOf("accept", "decline", "leave", "cancel"))
            HomeChallenges.parse(
                JSONObject(request(account, "api/community/challenges/${account.encodedUser}/$id", JSONObject().put("action", action))),
            )
        }

    suspend fun createChallenge(
        account: HomeAccount,
        body: JSONObject,
    ): HomeChallenges =
        withContext(Dispatchers.IO) {
            HomeChallenges.parse(JSONObject(request(account, "api/community/challenges/${account.encodedUser}", body)))
        }

    suspend fun markRead(
        account: HomeAccount,
        id: Long,
    ) = withContext(Dispatchers.IO) {
        request(account, "api/activity/${account.encodedUser}/read", JSONObject().put("ids", JSONArray().put(id)))
        Unit
    }

    suspend fun reply(
        account: HomeAccount,
        id: Long,
        message: String,
    ) = withContext(Dispatchers.IO) {
        request(account, "api/reply/${account.encodedUser}", JSONObject().put("notification", id).put("message", message))
        Unit
    }
}

internal object AnkiquestHomeData {
    fun account(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): HomeAccount? {
        val settings = preferences.all
        return HomeAccount.from(settings, (settings["username"] as? String).orEmpty())
    }

    val repository = HomeRepository({ account() })

    fun invalidate() = repository.invalidate()

    suspend fun local(): HomeLocal =
        CollectionManager.withCol {
            val rows =
                sched.deckDueTree().filter { it.did != 0L }.map {
                    HomeDeck(it.did, it.fullDeckName, it.newCount, it.lrnCount, it.revCount)
                }
            HomeLocal(rows, decks.selected())
        }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
