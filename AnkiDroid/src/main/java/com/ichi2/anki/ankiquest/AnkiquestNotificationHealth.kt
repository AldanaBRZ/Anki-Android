// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp

/** The inbox timestamp belongs to the authenticated server account that fetched it. */
object AnkiquestNotificationHealth {
    private const val SYNC_KEY = "ankiquestInboxSyncedAt:"

    fun recordSuccessfulSync(
        scope: String,
        at: Long,
        preferences: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        if (AnkiquestHomeData.account(preferences)?.scope != scope) return
        preferences.edit { putLong("$SYNC_KEY$scope", at) }
    }

    fun lastSuccessfulSync(preferences: SharedPreferences = AnkiDroidApp.sharedPrefs()): Long? {
        val account = AnkiquestHomeData.account(preferences)?.takeIf { it.token.isNotEmpty() } ?: return null
        return preferences.getLong("$SYNC_KEY${account.scope}", 0L).takeIf { it > 0L }
    }
}
