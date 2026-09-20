// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestDeckTreeTest {
    private fun tree(vararg names: String) =
        AnkiquestDeckTree(
            names.mapIndexed { index, name ->
                JSONObject().put("id", (index + 1).toString()).put("name", name).put("enabled", false)
            },
        )

    private fun AnkiquestDeckTree.names(indices: List<Int>) = indices.map { rows[it].name }

    @Test
    fun `a subdeck follows its parent, indented by its depth`() {
        val tree = tree("Spanish::Verbs::Irregular", "German", "Spanish", "Spanish::Nouns")
        assertEquals(
            listOf("German", "Spanish", "Spanish::Nouns", "Spanish::Verbs::Irregular"),
            tree.rows.map { it.name },
        )
        assertEquals(listOf(0, 0, 1, 2), tree.rows.map { it.depth })
        assertEquals(listOf("German", "Spanish", "Nouns", "Irregular"), tree.rows.map { it.label })
    }

    @Test
    fun `a deck that merely starts with the same letters is not a subdeck`() {
        val tree = tree("Spanish", "Spanish::Verbs", "Spanishly", "Spanish Extra")
        assertEquals(listOf("Spanish::Verbs"), tree.names(tree.descendants(tree.rows.indexOfFirst { it.name == "Spanish" })))
        assertTrue(tree.hasChildren(tree.rows.indexOfFirst { it.name == "Spanish" }))
        assertFalse(tree.hasChildren(tree.rows.indexOfFirst { it.name == "Spanishly" }))
    }

    @Test
    fun `collapsing a deck folds away everything under it`() {
        val tree = tree("Spanish", "Spanish::Verbs", "Spanish::Verbs::Irregular", "Spanish::Nouns", "German")
        val spanish = tree.rows.first { it.name == "Spanish" }.id
        val verbs = tree.rows.first { it.name == "Spanish::Verbs" }.id

        assertEquals(5, tree.visible(emptySet()).size)
        assertEquals(
            listOf("German", "Spanish", "Spanish::Nouns", "Spanish::Verbs"),
            tree.names(tree.visible(setOf(verbs))),
        )
        assertEquals(listOf("German", "Spanish"), tree.names(tree.visible(setOf(spanish))))
        assertEquals(
            listOf("German", "Spanish"),
            tree.names(tree.visible(setOf(spanish, verbs))),
            "a fold inside a fold changes nothing",
        )
    }

    @Test
    fun `an empty list has nothing to show`() {
        val tree = AnkiquestDeckTree(emptyList())
        assertEquals(0, tree.size)
        assertEquals(emptyList(), tree.visible(emptySet()))
    }
}
