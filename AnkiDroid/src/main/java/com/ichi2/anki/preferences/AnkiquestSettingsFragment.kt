/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.preferences

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.Ankiquest
import com.ichi2.anki.ankiquest.AnkiquestUpdater
import com.ichi2.preferences.VersatileTextPreference
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

class AnkiquestSettingsFragment : SettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest
    override val analyticsScreenNameConstant = "prefs.ankiquest"

    private var running = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun initSubscreen() {
        requirePreference<VersatileTextPreference>(R.string.ankiquest_url_key).continuousValidator =
            VersatileTextPreference.Validator { value ->
                if (value.isNotEmpty()) value.toHttpUrl()
            }
        requirePreference<SwitchPreferenceCompat>(R.string.ankiquest_notify_rank_key).setOnPreferenceChangeListener { _, enabled ->
            if (enabled == true) askForNotifications()
            true
        }
        requirePreference<ListPreference>(R.string.ankiquest_streak_hours_key).setOnPreferenceChangeListener { _, hours ->
            if (hours != "0") askForNotifications()
            true
        }
        bindAction(R.string.ankiquest_test_key) { Ankiquest.runFromSettings(requireContext(), uploadAll = false) }
        bindAction(R.string.ankiquest_upload_all_key) { Ankiquest.runFromSettings(requireContext(), uploadAll = true) }
        requirePreference<Preference>(R.string.ankiquest_check_updates_key).summary =
            getString(
                R.string.ankiquest_check_updates_summary,
                AnkiquestUpdater.installed() ?: getString(R.string.ankiquest_local_build),
            )
        bindAction(R.string.ankiquest_check_updates_key) { AnkiquestUpdater.checkNow(requireActivity()) }
    }

    private fun bindAction(
        key: Int,
        action: suspend () -> String?,
    ) {
        val preference = requirePreference<Preference>(key)
        val idleSummary = preference.summary
        preference.setOnPreferenceClickListener {
            if (running) return@setOnPreferenceClickListener true
            running = true
            preference.summary = getString(R.string.ankiquest_check_running)
            val context = requireContext()
            lifecycleScope.launch {
                val message =
                    try {
                        action()
                    } finally {
                        running = false
                        preference.summary = idleSummary
                    }
                if (message == null) return@launch
                AlertDialog
                    .Builder(context)
                    .setTitle(preference.title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        val prefs = preferenceManager.sharedPreferences ?: return
        if (!prefs.getBoolean(ASKED_KEY, false)) {
            prefs.edit { putBoolean(ASKED_KEY, true) }
            askForNotifications()
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val ASKED_KEY = "ankiquestAskedNotifications"
    }
}
