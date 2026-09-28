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

package com.ichi2.anki.ankiquest

import org.json.JSONObject

/** The flat deck list read as a tree, so "Spanish::Verbs" sits under "Spanish". */
class AnkiquestDeckTree(
    decks: List<JSONObject>,
) {
    class Row(
        val deck: JSONObject,
        val depth: Int,
        val label: String,
    ) {
        val id: String get() = deck.getString("id")
        val name: String get() = deck.getString("name")
    }

    val rows: List<Row> =
        decks
            .sortedWith { left, right -> compareNames(parts(left), parts(right)) }
            .map { deck ->
                val parts = parts(deck)
                Row(deck, parts.size - 1, parts.last())
            }

    val size get() = rows.size

    /** Every deck nested below this one. They follow it, because the list is sorted. */
    fun descendants(index: Int): List<Int> {
        val prefix = rows[index].name + "::"
        return (index + 1 until rows.size).takeWhile { rows[it].name.startsWith(prefix) }.toList()
    }

    fun hasChildren(index: Int): Boolean = descendants(index).isNotEmpty()

    /** The rows still on screen once every collapsed deck has folded its subtree away. */
    fun visible(collapsed: Set<String>): List<Int> {
        val shown = mutableListOf<Int>()
        var hiddenUntil = 0
        for (index in rows.indices) {
            if (index < hiddenUntil) continue
            shown += index
            if (rows[index].id in collapsed) {
                hiddenUntil = (descendants(index).lastOrNull() ?: index) + 1
            }
        }
        return shown
    }

    private companion object {
        fun parts(deck: JSONObject): List<String> = deck.getString("name").split("::")

        fun compareNames(
            left: List<String>,
            right: List<String>,
        ): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val order = left[index].compareTo(right[index], ignoreCase = true)
                if (order != 0) return order
            }
            return left.size - right.size
        }
    }
}
