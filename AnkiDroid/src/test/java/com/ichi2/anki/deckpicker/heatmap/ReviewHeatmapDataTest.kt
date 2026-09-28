// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.deckpicker.heatmap

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewHeatmapDataTest {
    private val today = LocalDate.of(2024, 3, 1)

    @Test
    fun `empty collection has no invented history or streak`() {
        val data = ReviewHeatmap.summarize(today, emptyMap(), emptyMap())
        assertEquals(0, data.currentStreak)
        assertEquals(0, data.longestStreak)
        assertEquals(0, data.daysStudied)
        assertEquals(0L, data.totalReviews)
        assertEquals(0.0, data.dailyAverage)
        assertNull(data.firstReviewDate)
        assertNull(ReviewHeatmap.searchForDay(data, today))
    }

    @Test
    fun `yesterday streak survives until today is over and includes leap day`() {
        val reviews = (1L..3L).associate { today.minusDays(it) to 10 }
        val data = ReviewHeatmap.summarize(today, reviews, emptyMap())
        assertEquals(3, data.currentStreak)
        assertEquals(3, data.longestStreak)
        assertEquals(3, data.daysStudied)
        assertEquals(30L, data.totalReviews)
        assertEquals(10.0, data.dailyAverage)
        assertEquals(LocalDate.of(2024, 2, 27), data.firstReviewDate)
        assertEquals("prop:rated=-1", ReviewHeatmap.searchForDay(data, LocalDate.of(2024, 2, 29)))

        val afterReview = ReviewHeatmap.summarize(today, reviews + (today to 2), emptyMap())
        assertEquals(4, afterReview.currentStreak)
        assertEquals(4, afterReview.longestStreak)
        assertEquals("prop:rated=0", ReviewHeatmap.searchForDay(afterReview, today))
    }

    @Test
    fun `gaps break current streak while retaining longest streak across year boundary`() {
        val date = LocalDate.of(2025, 1, 4)
        val reviews = (0L..3L).associate { LocalDate.of(2024, 12, 30).plusDays(it) to 2 }
        val data = ReviewHeatmap.summarize(date, reviews, emptyMap())
        assertEquals(0, data.currentStreak)
        assertEquals(4, data.longestStreak)
        assertEquals(2.0, data.dailyAverage)
    }

    @Test
    fun `totals do not overflow a daily integer and forecast cannot count as study`() {
        val data =
            ReviewHeatmap.summarize(
                today,
                mapOf(today to Int.MAX_VALUE, today.minusDays(1) to Int.MAX_VALUE, today.plusDays(1) to 99),
                mapOf(today to 2, today.plusDays(1) to 3, today.plusDays(90) to 4, today.plusDays(91) to 5),
            )
        assertEquals(4_294_967_294L, data.totalReviews)
        assertEquals(2, data.daysStudied)
        assertEquals(2, data.scheduled.size)
        assertEquals(today.plusDays(90), data.forecastEndDate)
        assertEquals("prop:due=1 -is:new", ReviewHeatmap.searchForDay(data, today.plusDays(1)))
        assertEquals("prop:due=90 -is:new", ReviewHeatmap.searchForDay(data, today.plusDays(90)))
        assertNull(ReviewHeatmap.searchForDay(data, today.plusDays(91)))
    }

    @Test
    fun `study date follows scheduler rollover through DST and midnight`() {
        val berlin = ZoneId.of("Europe/Berlin")
        for (date in listOf(LocalDate.of(2024, 3, 31), LocalDate.of(2024, 10, 27))) {
            for (rollover in listOf(0, 4, 23)) {
                val cutoff =
                    date
                        .plusDays(1)
                        .atTime(rollover, 0)
                        .atZone(berlin)
                        .toEpochSecond()
                assertEquals(date, ReviewHeatmap.studyDate(cutoff, berlin))
            }
        }
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val nextRollover =
            LocalDate
                .of(2024, 1, 1)
                .atTime(4, 0)
                .atZone(losAngeles)
                .toEpochSecond()
        assertEquals(LocalDate.of(2023, 12, 31), ReviewHeatmap.studyDate(nextRollover, losAngeles))
    }
}
