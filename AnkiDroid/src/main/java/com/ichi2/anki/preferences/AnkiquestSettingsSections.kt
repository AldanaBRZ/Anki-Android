// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.preferences

import com.ichi2.anki.R

class AnkiquestAccountSettingsFragment : AnkiquestSettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_account
    override val analyticsScreenNameConstant = "prefs.ankiquest.account"

    override fun initSubscreen() = bindAccount()
}

class AnkiquestStudySettingsFragment : AnkiquestSettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_study
    override val analyticsScreenNameConstant = "prefs.ankiquest.study"

    override fun initSubscreen() = bindStudy()
}

class AnkiquestNotificationsSettingsFragment : AnkiquestSettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_notifications
    override val analyticsScreenNameConstant = "prefs.ankiquest.notifications"

    override fun initSubscreen() = bindNotifications()
}

class AnkiquestSharingSettingsFragment : AnkiquestSettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_sharing
    override val analyticsScreenNameConstant = "prefs.ankiquest.sharing"

    override fun initSubscreen() = bindSharing()
}

class AnkiquestMaintenanceSettingsFragment : AnkiquestSettingsFragment() {
    override val preferenceResource = R.xml.preferences_ankiquest_maintenance
    override val analyticsScreenNameConstant = "prefs.ankiquest.maintenance"

    override fun initSubscreen() = bindMaintenance()
}
