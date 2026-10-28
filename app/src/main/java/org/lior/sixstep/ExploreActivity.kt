package org.lior.sixstep

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Explore (the user's layout draft, 2026-10-28): three groups that fold (Elements, Compositions, Building Blocks), a
 *  row for each piece: its name, and three pictures of consecutive steps, centred on the step of the piece that stands
 *  out most (Rarity), and under its name how it starts and ends. Drag a row sideways to move through its steps;
 *  dragging one puts the others back where they began. A tap opens the dance floor on the piece's first step. */
class ExploreActivity : AppCompatActivity() {
    private class Item(val piece: Piece, val title: String, val subtitle: String?, val cells: List<StepStrip.Cell>)
    private class Row(val strip: StepStrip, val centre: Int)
    private val rows = ArrayList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_explore)
        Ui.bars(this, Ui.PAGE_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.PAGE_BG)
        val list = findViewById<LinearLayout>(R.id.list)
        val data = DanceData.load(this); val store = CompositionStore(this)
        val start = getString(R.string.start)

        // every row says how the piece starts and ends (user 2026-10-28), and after that what else tells it apart
        fun item(p: Piece, more: String?): Item? {
            val c = p.compose(data, Piece.SWING)
            if (c.errors.isNotEmpty()) return null
            val t = Timeline(c, Piece.SWING)
            val facing = c.startState.replace('_', ' ') + " → " + c.endState.replace('_', ' ')
            return Item(p, titleCase(p.name), if (more == null) facing else facing + more, cellsOf(t, stopsOf(t), start))
        }
        val elements = data.names.flatMap { name ->
            data.variants(name).indices.mapNotNull { i -> Piece.find(data, store, Piece.ELEMENT, name, i)?.let { item(it, freeFoot(data, name, i)) } }
        }
        // the poses of every element: what usual looks like (the start positions aside)
        val corpus = elements.flatMap { it.cells.drop(1).mapNotNull { c -> Rarity.descriptor(c.feet) } }
        val compositions = store.compositions.mapNotNull { c -> Piece.find(data, store, Piece.COMPOSITION, c.name)?.let { item(it, null) } }
        val blocks = store.subs.mapNotNull { c -> Piece.find(data, store, Piece.BLOCK, c.name)?.let { item(it, " · " + c.elements.joinToString(", ")) } }
        group(list, R.string.elements, "elements", true, elements, corpus)
        group(list, R.string.compositions, "compositions", true, compositions, corpus)
        group(list, R.string.building_blocks, "blocks", false, blocks, corpus)
    }

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /** which foot is free, for a version of an element that starts and ends like another one */
    private fun freeFoot(data: DanceData, name: String, i: Int): String? {
        val vs = data.variants(name); val v = vs[i]
        val twin = vs.count { it.startState == v.startState && it.endState == v.endState } > 1
        return if (!twin) null else ", " + getString(if (v.leaderFreeStart == 'L') R.string.left_free else R.string.right_free)
    }

    /** a heading with its triangle (pointing at the heading when folded, down when open) and its rows */
    private fun group(list: LinearLayout, title: Int, key: String, openByDefault: Boolean, items: List<Item>, corpus: List<DoubleArray>) {
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        val header = layoutInflater.inflate(R.layout.explore_header, list, false)
        header.findViewById<TextView>(R.id.headerTitle).setText(title)
        val triangle = header.findViewById<ImageView>(R.id.headerTriangle)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        for (it in items) box.addView(rowView(box, it, corpus))
        var open = prefs.getBoolean("explore_open_$key", openByDefault)
        fun refresh() { box.visibility = if (open) View.VISIBLE else View.GONE; triangle.rotation = if (open) 0f else 90f }
        refresh()
        header.setOnClickListener { open = !open; prefs.edit().putBoolean("explore_open_$key", open).apply(); refresh() }
        list.addView(header); list.addView(box)
    }

    private fun rowView(parent: ViewGroup, item: Item, corpus: List<DoubleArray>): View {
        val v = layoutInflater.inflate(R.layout.explore_row, parent, false)
        val name = v.findViewById<View>(R.id.rowName)
        name.background = Ui.box(this, Ui.ROW, 1f, 0f, Ui.ROW_EDGE)
        v.findViewById<TextView>(R.id.rowTitle).text = item.title
        val sub = v.findViewById<TextView>(R.id.rowSubtitle)
        if (item.subtitle == null) sub.visibility = View.GONE else sub.text = item.subtitle
        val strip = v.findViewById<StepStrip>(R.id.rowStrip)
        strip.cellsAcross = 3f; strip.separators = true
        strip.cells = item.cells
        val row = Row(strip, Rarity.centre(item.cells, corpus))
        rows.add(row)
        strip.scrollToCell(row.centre, false)
        strip.onDragStart = { for (r in rows) if (r !== row) r.strip.scrollToCell(r.centre, true) }
        strip.onTap = { open(item.piece) }
        name.setOnClickListener { open(item.piece) }
        return v
    }

    /** the dance floor, always on the first step: the start position (user 2026-10-28) */
    private fun open(p: Piece) {
        startActivity(Intent(this, DanceFloorActivity::class.java).putExtra("kind", p.kind).putExtra("name", p.name).putExtra("variant", p.variant))
    }
}
