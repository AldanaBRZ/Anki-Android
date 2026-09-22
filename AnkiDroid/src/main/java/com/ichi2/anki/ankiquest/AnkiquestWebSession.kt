// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Credentials are handed only to known AnkiQuest documents, and live in the page's memory. */
internal data class AnkiquestWebSession(
    val dashboard: String,
    val user: String,
    private val token: String,
) {
    private val base = dashboard.toHttpUrlOrNull()
    private val routes = listOf("hour", "day", "week", "month", "year", "all", "records", "community")
    private val paths = base?.let { url -> listOf(url.encodedPath) + routes.map { url.encodedPath + it } }.orEmpty()

    val community: String
        get() = dashboard.substringBefore('#') + "community#reminders"

    fun allows(url: String?): Boolean {
        val expected = base ?: return false
        val actual = url?.toHttpUrlOrNull() ?: return false
        return expected.username.isEmpty() && expected.password.isEmpty() &&
            expected.encodedQuery == null &&
            actual.username.isEmpty() && actual.password.isEmpty() &&
            actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port &&
            actual.encodedPath in paths && actual.encodedQuery == null
    }

    fun script(url: String?): String? {
        if (!allows(url)) return null
        val expected = checkNotNull(base)
        val origin =
            expected
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .removeSuffix("/")
        val session = if (token.isEmpty()) "null" else JSONObject().put("user", user).put("token", token).toString()
        // A navigation can finish after the native check, so check the actual document again in JavaScript.
        return """
            (() => {
                const page = new URL(window.location.href);
                if (page.username || page.password || page.origin !== ${JSONObject.quote(origin)} ||
                    !${JSONArray(paths)}.includes(page.pathname) || page.search !== '') return;
                window.ankiquestSession = $session;
                window.dispatchEvent(new CustomEvent('ankiquest-auth'));
            })();
            """.trimIndent()
    }

    override fun toString(): String = "AnkiquestWebSession(dashboard=$dashboard, user=$user)"
}
