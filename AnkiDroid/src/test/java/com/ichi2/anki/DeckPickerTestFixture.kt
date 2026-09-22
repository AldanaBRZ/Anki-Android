// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.content.Intent
import androidx.core.content.edit
import androidx.recyclerview.widget.ConcatAdapter
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooper
import com.ichi2.anki.RobolectricTest.Companion.advanceRobolectricLooperUntil
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.widgets.DeckAdapter
import com.ichi2.testutils.BackupManagerTestUtilities

// TODO: move to testFixtures once RobolectricTest is moved

context(test: RobolectricTest)
fun withDeckPicker(
    deckCount: Int,
    withCards: Boolean = false,
    block: (DeckPicker) -> Unit,
) {
    // startup code occurs here so all users of this method are correctly setup
    test.ensureCollectionLoadIsSynchronous()
    test.setIntroductionSlidesShown(true)
    BackupManagerTestUtilities.setupSpaceForBackup(test.targetContext)
    // suppress the periodic 'backup your collection' prompt so the screenshot is just the deck list
    test.targetContext.sharedPrefs().edit { putBoolean("backupPromptDisabled", true) }
    if (withCards) test.ensureNonEmptyCollection()
    for (i in 0 until deckCount) {
        // 'Deck' is before 'Default' alphabetically
        test.addDeck("Test Deck $i")
    }
    val deckPicker =
        test.startActivityNormallyOpenCollectionWithIntent(DeckPicker::class.java, Intent()).also {
            advanceRobolectricLooper() // may be a fix for flaky tests
        }
    block(deckPicker)
}

/** Waits for a requested deck's real row without scrolling or rebinding it. */
fun DeckPicker.awaitDeckHolder(deckId: DeckId): DeckAdapter.ViewHolder {
    val decks = deckPickerBinding.decks
    val adapter = (decks.adapter as ConcatAdapter).adapters.filterIsInstance<DeckAdapter>().single()
    var holder: DeckAdapter.ViewHolder? = null
    // Draining the main looper once can finish before the background list diff posts its layout.
    advanceRobolectricLooperUntil(lazyMessage = { "Deck $deckId was not laid out" }) {
        val position = adapter.currentList.indexOfFirst { it.did == deckId }
        holder =
            if (position >= 0 && !decks.hasPendingAdapterUpdates()) {
                decks.findViewHolderForAdapterPosition(position) as? DeckAdapter.ViewHolder
            } else {
                null
            }
        holder != null
    }
    return checkNotNull(holder)
}
