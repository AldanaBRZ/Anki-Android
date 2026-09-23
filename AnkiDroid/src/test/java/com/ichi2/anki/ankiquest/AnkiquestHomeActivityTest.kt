// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.core.view.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestHomeActivityTest : RobolectricTest() {
    private var identity = HomeAccount("https://anki.example.test", "member", "secret")
    private lateinit var repository: HomeRepository
    private lateinit var snapshot: HomeRemote
    private val invitation =
        HomeChallenge(
            42,
            "Study together",
            "study_days",
            true,
            "friend",
            Long.MAX_VALUE,
            6,
            0,
            "active",
            listOf(HomeMember("member", "Member", "invited", 0), HomeMember("friend", "Friend", "accepted", 0)),
        )
    private val notice = HomeNotice(71, "Friend invited you", "A shared goal", "friend", false, 1_800_000_000, 42, true)

    @Before
    fun prepareHome() {
        repository = mockk(relaxed = true)
        snapshot =
            HomeRemote(
                identity.scope,
                HomeSection(JSONObject().put("level", 2).put("streak", 3)),
                HomeSection(HomeChallenges(listOf(invitation), listOf(HomeRecipient("friend", "Friend")))),
                HomeSection(HomeInbox(listOf(notice), 1, null, true)),
                1,
            )
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } answers { identity }
        every { AnkiquestHomeData.repository } returns repository
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        every { repository.cached(any()) } returns null
        coEvery { repository.load(any()) } answers { snapshot }
    }

    @After
    fun clearHomeMocks() {
        unmockkObject(AnkiquestHomeData)
    }

    @Test
    fun `offline Today keeps local study and all four native destinations`() {
        snapshot =
            snapshot.copy(
                profile = HomeSection(failure = HomeFailure.OFFLINE),
                challenges = HomeSection(failure = HomeFailure.OFFLINE),
                inbox = HomeSection(failure = HomeFailure.OFFLINE),
            )
        val home = launch()
        val nav = home.findViewById<BottomNavigationView>(R.id.aq_home_navigation)
        assertEquals(4, nav.menu.size)
        assertEquals(R.id.ankiquest_nav_today, nav.selectedItemId)
        assertTrue(home.hasText("Spanish"))
        home.click(home.getString(R.string.aq_home_study_due, 24))
        val study = assertNotNull(shadowOf(home).nextStartedActivity)
        assertEquals(DeckPicker::class.java.name, study.component?.className)
        assertEquals(12L, study.getLongExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK, 0))
        assertTrue(study.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, study.flags)
        nav.selectedItemId = R.id.ankiquest_nav_friends
        assertTrue(home.hasText(home.getString(R.string.aq_home_offline)))
        nav.selectedItemId = R.id.ankiquest_nav_progress
        assertTrue(home.hasText(home.getString(R.string.aq_home_statistics)))
        nav.selectedItemId = R.id.ankiquest_nav_decks
        val decks = assertNotNull(shadowOf(home).nextStartedActivity)
        assertFalse(decks.hasExtra(AnkiquestHomeActivity.EXTRA_STUDY_DECK))
        assertTrue(decks.getBooleanExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, false))
    }

    @Test
    fun `Friends reaches the invited goal and accepts with current owner`() {
        coEvery { repository.challengeAction(identity, 42, "accept") } returns
            HomeChallenges(
                listOf(
                    invitation.copy(
                        members =
                            invitation.members.map {
                                if (it.user ==
                                    "member"
                                ) {
                                    it.copy(status = "accepted")
                                } else {
                                    it
                                }
                            },
                    ),
                ),
                emptyList(),
            )
        val home = launch()
        home.findViewById<BottomNavigationView>(R.id.aq_home_navigation).selectedItemId = R.id.ankiquest_nav_friends
        home.click(home.getString(R.string.aq_home_view_goal))
        assertTrue(home.hasText(home.getString(R.string.aq_home_after_acceptance)))
        home.click(home.getString(R.string.aq_home_accept))
        advanceRobolectricLooper()
        coVerify(exactly = 1) { repository.challengeAction(identity, 42, "accept") }
        assertFalse(home.hasText(home.getString(R.string.aq_home_accept)))
        assertTrue(home.hasText(home.getString(R.string.aq_home_leave)))
    }

    @Test
    fun `Activity notification resolves exact goal and acknowledges once`() {
        val home = launch(AnkiquestHomeActivity.intent(targetContext, "activity", notificationId = 71))
        assertTrue(home.hasText("Study together"))
        assertTrue(home.hasText(home.getString(R.string.aq_home_after_acceptance)))
        coVerify(exactly = 1) { repository.markRead(identity, 71) }
        assertEquals(R.id.ankiquest_nav_friends, home.findViewById<BottomNavigationView>(R.id.aq_home_navigation).selectedItemId)
    }

    @Test
    fun `older notification resolves without silently routing to a different goal`() {
        snapshot = snapshot.copy(inbox = HomeSection(HomeInbox(emptyList(), 1, 100, true)))
        coEvery { repository.olderActivity(identity, 72) } returns HomeInbox(listOf(notice), 1, null, true)
        val home = launch(AnkiquestHomeActivity.intent(targetContext, "activity", notificationId = 71))
        assertTrue(home.hasText("Study together"))
        coVerify(exactly = 1) { repository.olderActivity(identity, 72) }
        coVerify(exactly = 1) { repository.markRead(identity, 71) }
    }

    @Test
    fun `notification for another member cannot open coincident challenge ids`() {
        val route =
            AnkiquestHomeActivity
                .intent(
                    targetContext,
                    "friends",
                    42,
                    71,
                ).putExtra(AnkiquestHomeActivity.EXTRA_ACCOUNT, "https://other.example.test/member")
        val home = launch(route)
        assertTrue(home.hasText(home.getString(R.string.aq_home_account_route_changed)))
        assertFalse(home.hasText(home.getString(R.string.aq_home_after_acceptance)))
        coVerify(exactly = 0) { repository.markRead(any(), any()) }
    }

    @Test
    fun `singleTop route changes destination and refreshes data`() {
        val controller =
            startActivityControllerNormallyOpenCollectionWithIntent(
                AnkiquestHomeActivity::class.java,
                AnkiquestHomeActivity.intent(targetContext),
            )
        controller.newIntent(AnkiquestHomeActivity.intent(targetContext, "progress"))
        advanceRobolectricLooper()
        assertTrue(controller.get().hasText(controller.get().getString(R.string.aq_home_statistics)))
        coVerify(atLeast = 2) { repository.load(identity) }
    }

    @Test
    fun `confirmation cannot send an old goal action after account changes`() {
        snapshot =
            snapshot.copy(
                challenges =
                    HomeSection(
                        HomeChallenges(
                            listOf(
                                invitation.copy(
                                    members =
                                        invitation.members.map {
                                            it.copy(status = "accepted")
                                        },
                                ),
                            ),
                            emptyList(),
                        ),
                    ),
            )
        val home = launch(AnkiquestHomeActivity.intent(targetContext, "friends", 42))
        home.click(home.getString(R.string.aq_home_leave))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        identity = identity.copy(server = "https://other.example.test", token = "new-secret")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceRobolectricLooper()
        coVerify(exactly = 0) { repository.challengeAction(any(), any(), any()) }
    }

    @Test
    fun `changing account while selecting friend abandons old invitation`() {
        val home = launch(AnkiquestHomeActivity.intent(targetContext, "friends"))
        home.click(home.getString(R.string.aq_home_create))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        identity = identity.copy(user = "different-member")
        dialog.listView.performItemClick(null, 0, 0)
        advanceRobolectricLooper()
        assertTrue(ShadowDialog.getLatestDialog() === dialog)
        coVerify(exactly = 0) { repository.createChallenge(any(), any()) }
    }

    private fun launch(intent: Intent = AnkiquestHomeActivity.intent(targetContext)): AnkiquestHomeActivity =
        startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, intent)

    private fun AnkiquestHomeActivity.hasText(value: String): Boolean =
        findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().any {
            it.text.toString() ==
                value
        }

    private fun AnkiquestHomeActivity.click(label: String) {
        val control =
            findViewById<View>(R.id.aq_home_content).allViews.filterIsInstance<TextView>().first {
                it.text.toString() == label &&
                    it.isClickable
            }
        assertTrue(control.isEnabled)
        control.performClick()
        advanceRobolectricLooper()
    }
}
