// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.preferences.PreferencesFragment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestNotificationHealthDialogTest : RobolectricTest() {
    @Test
    fun `notification status and actions are both visible`() {
        val intent = PreferencesActivity.getIntent(targetContext, AnkiquestSettingsFragment::class)
        val controller = Robolectric.buildActivity(PreferencesActivity::class.java, intent).setup()
        saveControllerForCleanup(controller)
        val fragment =
            (controller.get().fragment as PreferencesFragment)
                .childFragmentManager
                .findFragmentById(R.id.settings_container) as AnkiquestSettingsFragment
        val health = assertNotNull(fragment.findPreference<Preference>(targetContext.getString(R.string.ankiquest_health_key)))

        health.performClick()

        val dialog = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertTrue(
            assertNotNull(
                dialog.findViewById<TextView>(android.R.id.message),
            ).text.contains(targetContext.getString(R.string.ankiquest_health_last_sync, "")),
        )
        assertTrue(assertNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE)).isShown)
        val settingsButton = assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEUTRAL))
        assertTrue(settingsButton.isShown)
        settingsButton.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val settings = assertNotNull(ShadowDialog.getLatestDialog() as? AlertDialog)
        assertEquals(3, assertNotNull(settings.listView).adapter.count)
    }
}
