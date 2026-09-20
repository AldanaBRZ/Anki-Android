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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.R

/** Draws the deck tree for sharing: indented, collapsible, ticked in whole branches. */
class AnkiquestDeckAdapter(
    private val tree: AnkiquestDeckTree,
    val checked: BooleanArray,
    private val onChanged: () -> Unit,
) : RecyclerView.Adapter<AnkiquestDeckAdapter.Holder>() {
    private val collapsed = mutableSetOf<String>()
    private var shown = tree.visible(collapsed)

    class Holder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        val expand: ImageButton = view.findViewById(R.id.ankiquest_deck_expand)
        val check: CheckBox = view.findViewById(R.id.ankiquest_deck_check)
        val subdecks: ImageButton = view.findViewById(R.id.ankiquest_deck_subdecks)
    }

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_ankiquest_deck, parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val index = shown[position]
        val row = tree.rows[index]
        val children = tree.descendants(index)
        val density = holder.itemView.resources.displayMetrics.density
        holder.itemView.setPaddingRelative((row.depth * 16 * density).toInt(), 0, 0, 0)

        holder.check.setOnCheckedChangeListener(null)
        holder.check.text = row.label
        holder.check.isChecked = checked[index]
        holder.check.setOnCheckedChangeListener { _, value ->
            apply(index, value)
            onChanged()
        }

        holder.expand.visibility = if (children.isEmpty()) View.INVISIBLE else View.VISIBLE
        val folded = row.id in collapsed
        holder.expand.setImageResource(
            if (folded) R.drawable.ic_expand_more_black_24dp_xml else R.drawable.ic_expand_less_black_24dp,
        )
        holder.expand.contentDescription =
            holder.itemView.context.getString(
                if (folded) R.string.ankiquest_deck_expand else R.string.ankiquest_deck_collapse,
            )
        holder.expand.setOnClickListener {
            if (!collapsed.add(row.id)) collapsed.remove(row.id)
            refresh()
        }

        holder.subdecks.visibility = if (children.isEmpty()) View.GONE else View.VISIBLE
        holder.subdecks.setOnClickListener {
            apply(index, checked[index])
            onChanged()
        }
    }

    /** A branch shares one answer: ticking a deck ticks everything under it. */
    private fun apply(
        index: Int,
        value: Boolean,
    ) {
        checked[index] = value
        for (child in tree.descendants(index)) {
            checked[child] = value
        }
        refresh()
    }

    fun setAll(value: Boolean) {
        checked.fill(value)
        refresh()
    }

    fun anyUnchecked() = checked.any { !it }

    private fun refresh() {
        shown = tree.visible(collapsed)
        notifyDataSetChanged()
    }
}
