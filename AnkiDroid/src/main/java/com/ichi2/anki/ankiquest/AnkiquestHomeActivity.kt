// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.destinations.StatisticsDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** The daily study surface is native and local. Social data is an optional, independent layer. */
class AnkiquestHomeActivity : AnkiActivity(R.layout.activity_ankiquest_home) {
    private lateinit var content: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var activityButton: MaterialButton
    private var tab = "today"
    private var challengeId: Long? = null
    private var notificationId: Long? = null
    private var account: HomeAccount? = null
    private var local: HomeLocal? = null
    private var localFailed = false
    private var remote: HomeRemote? = null
    private var loading = false
    private var saving = false
    private var message: String? = null
    private var networkJob: Job? = null
    private var generation = 0
    private val readInFlight = mutableSetOf<Long>()
    private val repository get() = AnkiquestHomeData.repository
    private val back =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    challengeId != null || notificationId != null -> {
                        challengeId = null
                        notificationId = null
                    }
                    tab == "activity" -> tab = "friends"
                    else -> tab = "today"
                }
                render(resetScroll = true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.aq_home_content)
        scroll = findViewById(R.id.aq_home_scroll)
        activityButton = findViewById(R.id.aq_home_activity)
        activityButton.setTextColor(getColor(R.color.aq_home_text))
        activityButton.compoundDrawableTintList = ColorStateList.valueOf(getColor(R.color.aq_home_text))
        activityButton.setOnClickListener { showTab("activity") }
        findViewById<BottomNavigationView>(R.id.aq_home_navigation).setOnItemSelectedListener { item ->
            showTab(
                when (item.itemId) {
                    R.id.ankiquest_nav_friends -> "friends"
                    R.id.ankiquest_nav_progress -> "progress"
                    R.id.ankiquest_nav_decks -> "decks"
                    else -> "today"
                },
            )
            item.itemId != R.id.ankiquest_nav_decks
        }
        findViewById<View>(R.id.aq_home_account).setOnClickListener { settings() }
        onBackPressedDispatcher.addCallback(this, back)
        val root = findViewById<View>(R.id.aq_home_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        account = AnkiquestHomeData.account()
        if (savedInstanceState != null && savedInstanceState.getString(STATE_SCOPE) == account?.scope) {
            tab = validTab(savedInstanceState.getString(EXTRA_TAB))
            challengeId = savedInstanceState.getLong(EXTRA_CHALLENGE_ID).takeIf { it > 0 }
            notificationId = savedInstanceState.getLong(EXTRA_NOTIFICATION_ID).takeIf { it > 0 }
        } else {
            readRoute(intent)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRoute(intent)
        if (::content.isInitialized) {
            render(resetScroll = true)
            refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TAB, tab)
        outState.putString(STATE_SCOPE, account?.scope)
        challengeId?.let { outState.putLong(EXTRA_CHALLENGE_ID, it) }
        notificationId?.let { outState.putLong(EXTRA_NOTIFICATION_ID, it) }
    }

    private fun readRoute(intent: Intent) {
        tab = validTab(intent.getStringExtra(EXTRA_TAB))
        challengeId = intent.getLongExtra(EXTRA_CHALLENGE_ID, 0).takeIf { it > 0 }
        notificationId = intent.getLongExtra(EXTRA_NOTIFICATION_ID, 0).takeIf { it > 0 }
        val expected = intent.getStringExtra(EXTRA_ACCOUNT)
        if (expected != null && expected != AnkiquestHomeData.account()?.notificationAccount) {
            challengeId = null
            notificationId = null
            tab = "activity"
            message = getString(R.string.aq_home_account_route_changed)
        }
    }

    private fun refresh() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            remote = null
            challengeId = null
            notificationId = null
            message = null
            readInFlight.clear()
        }
        val turn = ++generation
        lifecycleScope.launch {
            try {
                val result = AnkiquestHomeData.local()
                if (turn == generation) {
                    local = result
                    localFailed = false
                    render()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest local study could not be loaded")
                if (turn == generation) {
                    localFailed = true
                    render()
                }
            }
        }
        networkJob?.cancel()
        remote = repository.cached(current)
        if (current == null) {
            loading = false
            render()
            return
        }
        loading = true
        render()
        networkJob =
            lifecycleScope.launch {
                try {
                    val result = repository.load(current)
                    if (turn == generation && AnkiquestHomeData.account()?.scope == current.scope) {
                        remote = result
                        resolveNotificationRoute(current)
                    }
                } catch (_: HomeAccountChanged) {
                    if (turn == generation) remote = null
                } finally {
                    if (turn == generation) {
                        loading = false
                        render()
                    }
                }
            }
    }

    private fun showTab(next: String) {
        if (next == "decks") {
            openDecks()
            return
        }
        tab = next
        challengeId = null
        notificationId = null
        message = null
        render(resetScroll = true)
    }

    private fun render(resetScroll: Boolean = false) {
        if (!::content.isInitialized) return
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            repository.cached(current)
            remote = null
            challengeId = null
            notificationId = null
            readInFlight.clear()
        }
        val position = if (resetScroll) 0 else scroll.scrollY
        content.removeAllViews()
        findViewById<View>(R.id.aq_home_loading).isVisible = loading || saving
        val unread =
            remote
                ?.inbox
                ?.takeIf { it.live }
                ?.value
                ?.unreadCount ?: 0
        activityButton.text = getString(R.string.aq_home_activity)
        activityButton.contentDescription = if (unread > 0) getString(R.string.aq_home_activity_unread, unread) else activityButton.text
        activityButton.setCompoundDrawablesWithIntrinsicBounds(if (unread > 0) R.drawable.ic_notifications else 0, 0, 0, 0)
        back.isEnabled = challengeId != null || notificationId != null || tab != "today"
        message?.let { text(content, it, small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        when {
            challengeId != null -> renderChallenge()
            notificationId != null -> renderMessage()
            tab == "friends" -> renderFriends()
            tab == "progress" -> renderProgress()
            tab == "activity" -> renderActivity()
            else -> renderToday()
        }
        renderNavigation()
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderToday() {
        title(R.string.ankiquest_nav_today, R.string.aq_home_today_subtitle)
        remote?.profile?.value?.let {
            text(content, getString(R.string.aq_home_streak_level, it.optInt("streak"), it.optInt("level")), small = true)
        }
        val study = card(tinted = true)
        text(study, getString(R.string.aq_home_next_step), small = true)
        val focus = local?.focus
        when {
            focus != null -> {
                text(study, focus.name, heading = true)
                text(study, getString(R.string.aq_home_counts, focus.review, focus.learning, focus.new), small = true)
                if (focus.due > 0) {
                    button(study, getString(R.string.aq_home_study_due, focus.due), primary = true) { study(focus.id) }
                } else {
                    text(study, getString(R.string.aq_home_nothing_due), small = true)
                }
                button(study, R.string.aq_home_change_deck) { chooseDeck() }
            }
            localFailed -> {
                text(study, getString(R.string.aq_home_local_unavailable))
                button(study, R.string.aq_home_open_decks) { openDecks() }
            }
            local == null -> text(study, getString(R.string.aq_home_loading_local))
            else -> {
                text(study, getString(R.string.aq_home_no_decks))
                button(study, R.string.aq_home_open_decks) { openDecks() }
            }
        }
        if (account == null) {
            connectionCard()
        } else {
            val section = remote?.challenges
            val challenge = sortedChallenges().firstOrNull { it.priority(account!!.user) < 2 }
            if (challenge != null) {
                challengeCard(challenge)
            } else if (!loading && section?.failure != null) {
                sectionWarning(section)
            }
        }
        AnkiquestStudySession.latest()?.let { summary ->
            val panel = card()
            text(panel, getString(R.string.aq_home_last_session), heading = true)
            text(panel, getString(R.string.aq_home_session_summary, summary.reviews, summary.duration(this), summary.remaining))
        }
        remote?.profile?.value?.optJSONArray("quests")?.objects()?.let { quests ->
            if (quests.isNotEmpty()) {
                text(content, getString(R.string.aq_home_quests), heading = true)
                quests.forEach { quest ->
                    val panel = card()
                    text(panel, quest.optString("title"), heading = true)
                    meter(panel, quest.optLong("progress"), quest.optLong("target"))
                    if (quest.optBoolean("done")) text(panel, getString(R.string.aq_home_quest_done, quest.optLong("reward")), small = true)
                }
            }
        }
        remote?.profile?.takeIf { it.failure != null }?.let { sectionWarning(it) }
        refreshButton()
    }

    private fun renderFriends() {
        title(R.string.ankiquest_nav_friends, R.string.aq_home_friends_subtitle)
        if (account?.token.isNullOrEmpty()) {
            connectionCard()
            return
        }
        sectionWarning(remote?.challenges)
        val items = sortedChallenges().filter { it.priority(account!!.user) < 2 }
        if (items.isEmpty() && !loading && remote?.challenges?.live == true) text(content, getString(R.string.aq_home_no_challenges))
        items.forEach(::challengeCard)
        button(content, R.string.aq_home_create, primary = true, enabled = canChangeChallenges()) { createGoal() }
        button(content, R.string.aq_home_activity) { showTab("activity") }
        button(content, R.string.aq_home_weekly_league) { web("/week") }
        button(content, R.string.aq_home_challenge_history) { web("/community#challenges") }
        button(content, R.string.aq_home_preferences_reminders) { web("/community#reminders") }
        refreshButton()
    }

    private fun sortedChallenges(): List<HomeChallenge> =
        remote?.challenges?.value?.items.orEmpty().sortedWith(
            compareBy<HomeChallenge> { it.priority(account?.user.orEmpty()) }.thenBy { it.endAt },
        )

    private fun challengeCard(challenge: HomeChallenge) {
        val panel = card()
        text(panel, status(challenge), small = true)
        text(panel, challenge.title, heading = true)
        text(panel, target(challenge))
        text(panel, getString(R.string.aq_home_deadline, date(challenge.endAt)), small = true)
        if (challenge.cooperative) {
            meter(panel, challenge.progress, challenge.target)
        } else {
            challenge.members.filter { it.status == "accepted" }.forEach {
                text(panel, getString(R.string.aq_home_member_progress, it.display, it.progress), small = true)
            }
        }
        if (challenge.membership(account?.user.orEmpty()) == "invited" && challenge.open()) {
            button(panel, R.string.aq_home_accept, primary = true, enabled = canChangeChallenges()) { act(challenge, "accept") }
            button(panel, R.string.aq_home_decline, enabled = canChangeChallenges()) { act(challenge, "decline") }
        }
        button(panel, R.string.aq_home_view_goal) {
            challengeId = challenge.id
            render(resetScroll = true)
        }
    }

    private fun renderChallenge() {
        backButton()
        sectionWarning(remote?.challenges)
        val goal =
            remote
                ?.challenges
                ?.value
                ?.items
                ?.firstOrNull { it.id == challengeId }
        if (goal == null) {
            text(content, getString(if (loading) R.string.aq_home_loading_remote else R.string.aq_home_not_found))
            refreshButton()
            return
        }
        text(content, goal.title, heading = true)
        text(content, status(goal), small = true)
        text(content, target(goal))
        text(content, getString(R.string.aq_home_deadline, date(goal.endAt)), small = true)
        text(content, getString(if (goal.cooperative) R.string.aq_home_shared_rule else R.string.aq_home_personal_rule))
        text(content, getString(R.string.aq_home_after_acceptance), small = true)
        if (goal.cooperative) meter(content, goal.progress, goal.target)
        goal.members.forEach { member ->
            val row = card()
            text(row, member.display, heading = true)
            when (member.status) {
                "accepted" -> meter(row, member.progress, goal.target)
                "invited" -> text(row, getString(R.string.aq_home_invited), small = true)
                else -> text(row, getString(R.string.aq_home_member_left, member.display), small = true)
            }
        }
        if (goal.open()) {
            when (goal.membership(account?.user.orEmpty())) {
                "invited" -> {
                    button(content, R.string.aq_home_accept, primary = true, enabled = canChangeChallenges()) { act(goal, "accept") }
                    button(content, R.string.aq_home_decline, enabled = canChangeChallenges()) { act(goal, "decline") }
                }
                "accepted" -> {
                    local?.focus?.takeIf { it.due > 0 }?.let { deck ->
                        button(content, getString(R.string.aq_home_study_due, deck.due), primary = true) { study(deck.id) }
                    }
                    val creator = goal.creator == account?.user
                    button(
                        content,
                        if (creator) R.string.aq_home_cancel_goal else R.string.aq_home_leave,
                        enabled = canChangeChallenges(),
                    ) {
                        val scope = account?.scope
                        MaterialAlertDialogBuilder(
                            this,
                        ).setMessage(if (creator) R.string.aq_home_confirm_close else R.string.aq_home_confirm_leave)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ -> act(goal, if (creator) "cancel" else "leave", scope) }
                            .show()
                    }
                }
            }
        }
    }

    private fun renderProgress() {
        title(R.string.ankiquest_nav_progress, R.string.aq_home_progress_subtitle)
        button(content, R.string.aq_home_statistics) { navigate(StatisticsDestination) }
        if (account == null) {
            connectionCard()
            return
        }
        sectionWarning(remote?.profile)
        remote?.profile?.value?.let { profile ->
            val panel = card(tinted = true)
            text(panel, getString(R.string.aq_home_streak_level, profile.optInt("streak"), profile.optInt("level")), heading = true)
            meter(panel, profile.optLong("xp_into_level"), profile.optLong("xp_for_next"))
            text(content, getString(R.string.aq_home_consistency), heading = true)
            profile.optJSONArray("heatmap")?.objects()?.takeLast(7)?.asReversed()?.forEach {
                val day = it.optString("date")
                val reviews = it.optLong("reviews")
                text(
                    content,
                    when {
                        reviews > 0 -> getString(R.string.aq_home_study_day, day, reviews)
                        it.optBoolean("frozen") -> getString(R.string.aq_home_protected_day, day)
                        else -> getString(R.string.aq_home_no_study_day, day)
                    },
                    small = true,
                )
            }
            text(content, getString(R.string.aq_home_achievements), heading = true)
            profile.optJSONArray("achievements")?.objects()?.sortedBy { it.isNull("unlocked") }?.take(5)?.forEach {
                val achievement = card()
                text(achievement, it.optString("title"), heading = true)
                text(achievement, it.optString("description"), small = true)
                if (!it.isNull("unlocked")) {
                    text(achievement, getString(R.string.aq_home_achievement_unlocked, it.optString("unlocked")), small = true)
                } else {
                    meter(achievement, it.optLong("progress"), it.optLong("target"))
                }
            }
        }
        button(content, R.string.aq_home_all_achievements) { web(profilePath()) }
        button(content, R.string.aq_home_full_history) { web("/community#records") }
        refreshButton()
    }

    private fun renderActivity() {
        title(R.string.aq_home_activity)
        if (account?.token.isNullOrEmpty()) {
            connectionCard()
            return
        }
        sectionWarning(remote?.inbox)
        val inbox = remote?.inbox?.value
        if (inbox != null) {
            text(content, getString(if (inbox.modern) R.string.aq_home_inbox_retention else R.string.aq_home_inbox_legacy), small = true)
            if (inbox.items.isEmpty()) text(content, getString(R.string.aq_home_inbox_empty))
            inbox.items.forEach { notice ->
                val row = card()
                if (notice.unread && inbox.modern) text(row, getString(R.string.aq_home_unread), small = true)
                text(row, notice.title, heading = true)
                text(row, notice.body)
                text(row, date(notice.createdAt), small = true)
                button(
                    row,
                    if (notice.challengeId !=
                        null
                    ) {
                        R.string.aq_home_view_goal
                    } else {
                        R.string.aq_home_open_message
                    },
                ) { openNotice(notice) }
            }
            inbox.nextBefore?.let { before ->
                button(content, R.string.aq_home_older, enabled = remote?.inbox?.live == true && !saving) {
                    mutate { captured ->
                        val next = repository.olderActivity(captured, before)
                        remote = remote?.copy(inbox = HomeSection(next.copy(items = (inbox.items + next.items).distinctBy { it.id })))
                    }
                }
            }
        }
        refreshButton()
    }

    private fun openNotice(notice: HomeNotice) {
        if (notice.challengeId != null) {
            tab = "friends"
            challengeId = notice.challengeId
            notificationId = null
        } else {
            notificationId = notice.id
        }
        render(resetScroll = true)
        markRead(notice)
    }

    private fun markRead(notice: HomeNotice) {
        val captured = account ?: return
        if (!notice.unread || remote?.inbox?.live != true || remote?.inbox?.value?.modern != true || !readInFlight.add(notice.id)) return
        lifecycleScope.launch {
            try {
                repository.markRead(captured, notice.id)
                if (captured.scope != AnkiquestHomeData.account()?.scope) return@launch
                val inbox = remote?.inbox?.value ?: return@launch
                if (inbox.items.none { it.id == notice.id && it.unread }) return@launch
                remote =
                    remote?.copy(
                        inbox =
                            HomeSection(
                                inbox.copy(
                                    items =
                                        inbox.items.map {
                                            if (it.id ==
                                                notice.id
                                            ) {
                                                it.copy(unread = false)
                                            } else {
                                                it
                                            }
                                        },
                                    unreadCount = inbox.unreadCount?.minus(1)?.coerceAtLeast(0),
                                ),
                            ),
                    )
                render()
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (
                _: Exception,
            ) {
                // Reading content does not depend on the acknowledgement.
            } finally {
                readInFlight.remove(notice.id)
            }
        }
    }

    private suspend fun resolveNotificationRoute(captured: HomeAccount) {
        val id = notificationId ?: return
        var notice =
            remote
                ?.inbox
                ?.value
                ?.items
                ?.firstOrNull { it.id == id }
        if (notice == null && remote?.inbox?.live == true && remote?.inbox?.value?.modern == true && id < Long.MAX_VALUE) {
            try {
                val page = repository.olderActivity(captured, id + 1)
                notice = page.items.firstOrNull { it.id == id }
                if (notice != null) {
                    val inbox = remote?.inbox?.value ?: return
                    remote = remote?.copy(inbox = HomeSection(inbox.copy(items = (inbox.items + notice).distinctBy { it.id })))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        }
        if (captured.scope != AnkiquestHomeData.account()?.scope || notice == null) return
        notice.challengeId?.let {
            challengeId = it
            tab = "friends"
        }
        markRead(notice)
    }

    private fun renderMessage() {
        backButton()
        val notice =
            remote
                ?.inbox
                ?.value
                ?.items
                ?.firstOrNull { it.id == notificationId }
        if (notice == null) {
            text(content, getString(if (loading) R.string.aq_home_loading_remote else R.string.aq_home_notification_missing))
            button(content, R.string.aq_home_activity) { showTab("activity") }
            return
        }
        text(content, notice.title, heading = true)
        text(content, notice.body)
        text(content, date(notice.createdAt), small = true)
        if (notice.replied) {
            text(content, getString(R.string.aq_home_reply_already), small = true)
        } else if (notice.sender.isNotBlank() && notice.sender != account?.user) {
            button(
                content,
                R.string.aq_home_reply,
                primary = true,
                enabled = remote?.inbox?.live == true && !saving && !loading,
            ) { reply(notice) }
        }
    }

    private fun reply(notice: HomeNotice) {
        val expectedScope = account?.scope
        val input =
            EditText(this).apply {
                hint = getString(R.string.aq_home_reply_hint)
                filters = arrayOf(InputFilter.LengthFilter(200))
                minHeight =
                    dp(48)
            }
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.aq_home_reply)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.aq_home_reply, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > 200 || value.any { it.isISOControl() }) {
                    input.error = getString(R.string.aq_home_reply_length)
                } else {
                    dialog.dismiss()
                    mutate(expectedScope) { captured ->
                        repository.reply(captured, notice.id, value)
                        message = getString(R.string.aq_home_reply_sent)
                        remote = repository.load(captured)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun act(
        goal: HomeChallenge,
        action: String,
        expectedScope: String? = account?.scope,
    ) {
        mutate(expectedScope) { captured ->
            val result = repository.challengeAction(captured, goal.id, action)
            remote = remote?.copy(challenges = HomeSection(result))
            if (action == "decline" || action == "leave") challengeId = null
            message = getString(R.string.aq_home_action_saved)
        }
    }

    private fun createGoal() {
        val expectedScope = account?.scope
        val people =
            remote
                ?.challenges
                ?.value
                ?.recipients
                .orEmpty()
        if (people.isEmpty()) {
            message = getString(R.string.aq_home_no_recipients)
            render()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aq_home_choose_friend)
            .setItems(people.map { it.display }.toTypedArray()) { _, index -> choosePreset(people[index], expectedScope) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choosePreset(
        person: HomeRecipient,
        expectedScope: String?,
    ) {
        if (expectedScope != AnkiquestHomeData.account()?.scope) return
        val labels =
            listOf(
                R.string.aq_home_preset_shared,
                R.string.aq_home_preset_personal,
                R.string.aq_home_preset_reviews,
            ).map(::getString)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aq_home_choose_preset)
            .setItems(labels.toTypedArray()) { _, selected ->
                val request =
                    JSONObject()
                        .put("title", labels[selected])
                        .put("kind", if (selected == 2) "reviews" else "study_days")
                        .put("cooperative", selected != 1)
                        .put(
                            "target",
                            when (selected) {
                                1 -> 3
                                2 -> 100
                                else -> 6
                            },
                        ).put("duration_days", 7)
                        .put("recipients", JSONArray().put(person.user))
                if (remote?.inbox?.value?.modern == true) request.put("request_id", UUID.randomUUID().toString())
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.aq_home_create_review, person.display, labels[selected]))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.aq_home_send_invite) { _, _ ->
                        mutate(expectedScope) { captured ->
                            val previous =
                                remote
                                    ?.challenges
                                    ?.value
                                    ?.items
                                    .orEmpty()
                                    .map { it.id }
                                    .toSet()
                            val result = repository.createChallenge(captured, request)
                            remote = remote?.copy(challenges = HomeSection(result))
                            result.items.firstOrNull { it.id !in previous && it.creator == captured.user }?.let {
                                tab = "friends"
                                challengeId = it.id
                                notificationId = null
                            }
                            message = getString(R.string.aq_home_created)
                        }
                    }.show()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mutate(
        expectedScope: String? = account?.scope,
        block: suspend (HomeAccount) -> Unit,
    ) {
        val captured = account ?: return
        if (saving || captured.scope != expectedScope || captured.scope != AnkiquestHomeData.account()?.scope ||
            captured.token.isEmpty()
        ) {
            return
        }
        networkJob?.cancel()
        generation++
        loading = false
        repository.invalidate()
        saving = true
        message = null
        render()
        lifecycleScope.launch {
            try {
                block(captured)
                if (captured.scope != AnkiquestHomeData.account()?.scope) {
                    remote = null
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HomeHttpException) {
                if (e.code == 401 || e.code == 403) {
                    remote = null
                    message = getString(R.string.aq_home_auth)
                } else {
                    message = e.reason?.take(240) ?: getString(R.string.aq_home_action_failed)
                }
            } catch (_: HomeAccountChanged) {
                remote = null
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest action not confirmed")
                message = getString(R.string.aq_home_action_failed)
            } finally {
                saving = false
                render()
            }
        }
    }

    private fun canChangeChallenges(): Boolean = !saving && !loading && remote?.challenges?.live == true && !account?.token.isNullOrEmpty()

    private fun chooseDeck() {
        val rows = local?.decks.orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.aq_home_change_deck)
            .setItems(rows.map { "${it.name} · ${it.due}" }.toTypedArray()) { _, index ->
                lifecycleScope.launch {
                    try {
                        CollectionManager.withCol {
                            if (decks.get(rows[index].id) != null) decks.select(rows[index].id)
                        }
                        local = AnkiquestHomeData.local()
                        localFailed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "AnkiQuest selected deck could not be loaded")
                        localFailed = true
                        local = null
                    }
                    render()
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun study(deck: Long) = openDecks(deck)

    private fun openDecks(deck: Long? = null) {
        startActivity(
            Intent(this, DeckPicker::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_SKIP_HOME, true)
                deck?.let { putExtra(EXTRA_STUDY_DECK, it) }
            },
        )
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun web(path: String) {
        startActivity(Intent(this, AnkiquestActivity::class.java).putExtra(AnkiquestActivity.EXTRA_PATH, path))
    }

    private fun profilePath(): String = "/#${account?.encodedUser.orEmpty()}"

    private fun connectionCard() {
        val panel = card()
        text(panel, getString(R.string.aq_home_connected))
        button(panel, R.string.aq_home_connect) { settings() }
    }

    private fun sectionWarning(section: HomeSection<*>?) {
        if (section?.failure == null) return
        text(
            content,
            getString(
                if (section.cached) {
                    R.string.aq_home_cached
                } else {
                    when (section.failure) {
                        HomeFailure.AUTH -> R.string.aq_home_auth
                        HomeFailure.OFFLINE -> R.string.aq_home_offline
                        HomeFailure.UNSUPPORTED -> R.string.aq_home_unsupported
                        else -> R.string.aq_home_server_error
                    }
                },
            ),
            small = true,
        )
        if (section.failure == HomeFailure.AUTH) button(content, R.string.aq_home_settings) { settings() }
    }

    private fun refreshButton() = button(content, R.string.aq_home_retry, enabled = !loading && !saving) { refresh() }

    private fun backButton() = button(content, R.string.aq_home_back) { back.handleOnBackPressed() }

    private fun title(
        @StringRes title: Int,
        @StringRes subtitle: Int? = null,
    ) {
        text(content, getString(title), heading = true, large = true)
        subtitle?.let { text(content, getString(it), small = true) }
    }

    private fun card(tinted: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(if (tinted) R.color.aq_home_tint else R.color.aq_home_surface)
            content.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(12)
                    ; bottomMargin = dp(4)
                },
            )
        }

    private fun text(
        parent: LinearLayout,
        value: String,
        heading: Boolean = false,
        small: Boolean = false,
        large: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize =
                if (large) {
                    26f
                } else if (heading) {
                    18f
                } else if (small) {
                    14f
                } else {
                    16f
                }
            setTextColor(getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
            if (heading) {
                typeface = Typeface.DEFAULT_BOLD
                ViewCompat.setAccessibilityHeading(this, true)
            }
            setPadding(0, dp(5), 0, dp(5))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun button(
        parent: LinearLayout,
        @StringRes label: Int,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): MaterialButton = button(parent, getString(label), primary, enabled, click)

    private fun button(
        parent: LinearLayout,
        label: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            minHeight = dp(48)
            minimumHeight = dp(48)
            cornerRadius = dp(12)
            isEnabled = enabled
            setTextColor(getColor(if (primary) R.color.aq_home_primary_text else R.color.aq_home_text))
            backgroundTintList = ColorStateList.valueOf(getColor(if (primary) R.color.aq_home_primary else R.color.aq_home_surface))
            if (!primary) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(getColor(R.color.aq_home_outline))
            }
            setOnClickListener { click() }
            parent.addView(
                this,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(6)
                },
            )
        }

    private fun meter(
        parent: LinearLayout,
        value: Long,
        max: Long,
    ) {
        text(parent, getString(R.string.aq_home_progress_fraction, value, max), small = true)
        parent.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                this.max = 1000
                progress = if (max <= 0) 0 else (value.toDouble() / max * 1000).coerceIn(0.0, 1000.0).toInt()
                progressTintList = ColorStateList.valueOf(getColor(R.color.aq_home_primary))
                contentDescription = getString(R.string.aq_home_progress_fraction, value, max)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { bottomMargin = dp(6) },
        )
    }

    private fun renderNavigation() {
        val selected =
            when (tab) {
                "friends", "activity" -> R.id.ankiquest_nav_friends
                "progress" -> R.id.ankiquest_nav_progress
                else -> R.id.ankiquest_nav_today
            }
        findViewById<BottomNavigationView>(R.id.aq_home_navigation).menu.findItem(selected).isChecked = true
    }

    private fun target(goal: HomeChallenge): String =
        getString(
            when {
                goal.kind == "study_days" && goal.cooperative -> R.string.aq_home_target_days_shared
                goal.kind == "study_days" -> R.string.aq_home_target_days_personal
                goal.cooperative -> R.string.aq_home_target_reviews_shared
                else -> R.string.aq_home_target_reviews_personal
            },
            goal.target,
        )

    private fun status(goal: HomeChallenge): String =
        getString(
            when {
                goal.membership(account?.user.orEmpty()) == "invited" && goal.open() -> R.string.aq_home_invited
                goal.status == "complete" -> R.string.aq_home_challenge_complete
                goal.status == "cancelled" -> R.string.aq_home_challenge_cancelled
                goal.status == "ended" -> R.string.aq_home_challenge_ended
                else -> R.string.aq_home_challenge_active
            },
        )

    private fun shape(
        @ColorRes color: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(getColor(color))
        }

    private fun date(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(
            Date(
                if (timestamp <
                    10_000_000_000L
                ) {
                    timestamp * 1000
                } else {
                    timestamp
                },
            ),
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SKIP_HOME = "ankiquest.skip_home"
        const val EXTRA_STUDY_DECK = "ankiquest.study_deck"
        const val EXTRA_TAB = "ankiquest.tab"
        const val EXTRA_CHALLENGE_ID = "ankiquest.challenge_id"
        const val EXTRA_NOTIFICATION_ID = "ankiquest.notification_id"
        const val EXTRA_ACCOUNT = "ankiquest.account"
        private const val STATE_SCOPE = "ankiquest.home_scope"

        fun intent(
            context: Context,
            tab: String = "today",
            challengeId: Long? = null,
            notificationId: Long? = null,
        ): Intent =
            Intent(context, AnkiquestHomeActivity::class.java).apply {
                putExtra(EXTRA_TAB, validTab(tab))
                challengeId?.let { putExtra(EXTRA_CHALLENGE_ID, it) }
                notificationId?.let { putExtra(EXTRA_NOTIFICATION_ID, it) }
                AnkiquestHomeData.account()?.let { putExtra(EXTRA_ACCOUNT, it.notificationAccount) }
            }

        internal fun validTab(tab: String?): String = tab?.takeIf { it in setOf("today", "friends", "progress", "activity") } ?: "today"
    }
}
