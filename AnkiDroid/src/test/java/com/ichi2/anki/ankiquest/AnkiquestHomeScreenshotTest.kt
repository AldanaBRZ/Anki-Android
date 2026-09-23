// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Opt-in visual QA; the normal unit-test task excludes ScreenshotTestCategory. */
@Config(sdk = [33])
class AnkiquestHomeScreenshotTest : ScreenshotTest() {
    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) {
            RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
        }
    }

    @Before
    fun prepareHome() {
        val identity = HomeAccount("https://anki.example.test", "sam", "visual-test-token")
        val invitation =
            HomeChallenge(
                42,
                "A week of steady study",
                "study_days",
                true,
                "rin",
                1_900_000_000_000L,
                6,
                1,
                "active",
                listOf(HomeMember("sam", "Sam", "invited", 0), HomeMember("rin", "Rin", "accepted", 1)),
            )
        val notice =
            HomeNotice(
                71,
                "Rin invited you",
                "Reach 6 study days together over 7 days. Join when it suits you.",
                "rin",
                false,
                1_789_992_000,
                42,
                true,
            )
        val profile =
            JSONObject()
                .put("level", 4)
                .put("streak", 3)
                .put("xp_into_level", 120)
                .put("xp_for_next", 250)
                .put(
                    "quests",
                    JSONArray().put(
                        JSONObject()
                            .put("title", "A little practice")
                            .put("progress", 6)
                            .put("target", 10)
                            .put("done", false),
                    ),
                ).put(
                    "heatmap",
                    JSONArray()
                        .put(
                            JSONObject().put("date", "2026-09-20").put("reviews", 12),
                        ).put(
                            JSONObject().put("date", "2026-09-21").put("reviews", 8),
                        ).put(JSONObject().put("date", "2026-09-22").put("reviews", 6)),
                ).put(
                    "achievements",
                    JSONArray().put(
                        JSONObject()
                            .put(
                                "title",
                                "A steady start",
                            ).put("description", "Study on three days.")
                            .put("progress", 3)
                            .put("target", 3)
                            .put("unlocked", "2026-09-22"),
                    ),
                )
        val snapshot =
            HomeRemote(
                identity.scope,
                HomeSection(profile),
                HomeSection(HomeChallenges(listOf(invitation), listOf(HomeRecipient("rin", "Rin")))),
                HomeSection(HomeInbox(listOf(notice), 1, null, true)),
                1,
            )
        val repository = mockk<HomeRepository>(relaxed = true)
        mockkObject(AnkiquestHomeData)
        every { AnkiquestHomeData.account(any()) } returns identity
        every { AnkiquestHomeData.repository } returns repository
        coEvery { AnkiquestHomeData.local() } returns HomeLocal(listOf(HomeDeck(12, "Spanish", 3, 2, 19)), 12)
        every { repository.cached(any()) } returns null
        coEvery { repository.load(any()) } returns snapshot
    }

    @After
    fun clearHomeMocks() {
        unmockkObject(AnkiquestHomeData)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun today() = captureTab("today")

    @Test
    fun friends() = captureTab("friends")

    @Test
    fun progress() = captureTab("progress")

    @Test
    fun activity() = captureTab("activity")

    @Test
    fun todayLargeText() = captureTab("today", largeText = true)

    @Test
    fun friendsLargeText() = captureTab("friends", largeText = true)

    @Test
    fun progressLargeText() = captureTab("progress", largeText = true)

    @Test
    fun activityLargeText() = captureTab("activity", largeText = true)

    private fun captureTab(
        tab: String,
        largeText: Boolean = false,
    ) {
        RuntimeEnvironment.setFontScale(if (largeText) 2f else 1f)
        startActivityNormallyOpenCollectionWithIntent(AnkiquestHomeActivity::class.java, AnkiquestHomeActivity.intent(targetContext, tab))
        advanceRobolectricLooper()
        val size = if (device == DeviceConfig.PHONE) "320dp" else "default"
        captureScreen("${tab}_${size}${if (largeText) "_200percent" else ""}")
    }
}
