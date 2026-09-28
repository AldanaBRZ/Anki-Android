// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestCompanionTest : RobolectricTest() {
    private fun account(user: String): HomeAccount {
        val preferences = AnkiDroidApp.sharedPrefs()
        preferences
            .edit()
            .putString(Ankiquest.URL_KEY, "https://example.com")
            .putString(Ankiquest.USER_KEY, user)
            .putString(Ankiquest.TOKEN_KEY, "token-$user")
            .commit()
        return requireNotNull(AnkiquestHomeData.account())
    }

    @Test
    fun `choice follows only the current account and maps all mascot poses`() {
        val alice = account("alice")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertTrue(AnkiquestCompanion.remember(alice, "ankilope"))
        assertEquals("ankilope", AnkiquestCompanion.selected())
        assertEquals(R.drawable.ankilope_study, AnkiquestCompanion.resource(R.drawable.aki_freeze))
        assertEquals(R.drawable.ankilope_celebrate, AnkiquestCompanion.resource(R.drawable.aki_winner))

        val bob = account("bob")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertFalse(AnkiquestCompanion.remember(alice, "none"))
        assertTrue(AnkiquestCompanion.remember(bob, "none"))
        assertFalse(AnkiquestCompanion.visible())

        account("alice")
        assertEquals("ankilope", AnkiquestCompanion.selected())
    }
}
