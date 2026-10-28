package org.lior.sixstep

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu

/** Explore (the user's layout draft, 2026-10-28): three groups that fold (Elements, Compositions, Building Blocks), one
 *  row for each piece: its name, its version box at the right of the name (where it starts and ends, and the leader's
 *  free foot; for an element with several versions a dropdown that chooses which one the row shows, remembered), and
 *  three pictures of consecutive steps, centred on the step that stands out most (Rarity). Drag a row sideways to move
 *  through its steps; dragging one puts the others back where they began. A tap opens the dance floor on the first step
 *  of the version shown. */
class ExploreActivity : AppCompatActivity() {
    /** one way to dance a row's piece: an element from one starting condition, or the one way of a composition */
    private class Version(val piece: Piece, val label: String, val cells: List<StepStrip.Cell>)
    private class Row(val strip: StepStrip, var centre: Int)
    private val rows = ArrayList<Row>()
    private var corpus: List<DoubleArray> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_explore)
        Ui.bars(this, Ui.PAGE_BG)
        findViewById<View>(R.id.root).setBackgroundColor(Ui.PAGE_BG)
        val list = findViewById<LinearLayout>(R.id.list)
        val data = DanceData.load(this); val store = CompositionStore(this)
        val start = getString(R.string.start)

        fun version(p: Piece): Version? {
            val c = p.compose(data, Piece.SWING)
            if (c.errors.isNotEmpty()) return null
            val t = Timeline(c, Piece.SWING)
            val label = c.startState.replace('_', ' ') + " → " + c.endState.replace('_', ' ') + "\n" +
                getString(if (c.startFree == 'L') R.string.left_free else R.string.right_free)
            return Version(p, label, cellsOf(t, stopsOf(t), start))
        }
        // one row per element, its versions in the row's dropdown (user 2026-10-28)
        val elements = data.names.mapNotNull { name ->
            val vs = data.variants(name).indices.mapNotNull { i -> Piece.find(data, store, Piece.ELEMENT, name, i)?.let { version(it) } }
            if (vs.isEmpty()) null else Pair(name, vs)
        }
        // the poses of every version of every element: what usual looks like (the start positions aside)
        corpus = elements.flatMap { (_, vs) -> vs.flatMap { v -> v.cells.drop(1).mapNotNull { Rarity.descriptor(it.feet) } } }
        val compositions = store.compositions.mapNotNull { c -> Piece.find(data, store, Piece.COMPOSITION, c.name)?.let { version(it) }?.let { Pair(c.name, listOf(it)) } }
        val blocks = store.subs.mapNotNull { c -> Piece.find(data, store, Piece.BLOCK, c.name)?.let { version(it) }?.let { Pair(c.name, listOf(it)) } }
        group(list, R.string.elements, "elements", true, elements)
        group(list, R.string.compositions, "compositions", true, compositions)
        group(list, R.string.building_blocks, "blocks", false, blocks)
    }

    private fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /** a heading with its triangle (pointing at the heading when folded, down when open) and its rows */
    private fun group(list: LinearLayout, title: Int, key: String, openByDefault: Boolean, items: List<Pair<String, List<Version>>>) {
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        val header = layoutInflater.inflate(R.layout.explore_header, list, false)
        header.findViewById<TextView>(R.id.headerTitle).setText(title)
        val triangle = header.findViewById<ImageView>(R.id.headerTriangle)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        for ((name, versions) in items) box.addView(rowView(box, name, versions))
        var open = prefs.getBoolean("explore_open_$key", openByDefault)
        fun refresh() { box.visibility = if (open) View.VISIBLE else View.GONE; triangle.rotation = if (open) 0f else 90f }
        refresh()
        header.setOnClickListener { open = !open; prefs.edit().putBoolean("explore_open_$key", open).apply(); refresh() }
        list.addView(header); list.addView(box)
    }

    private fun rowView(parent: ViewGroup, name: String, versions: List<Version>): View {
        val prefs = getSharedPreferences("6step", MODE_PRIVATE)
        val v = layoutInflater.inflate(R.layout.explore_row, parent, false)
        val nameBox = v.findViewById<View>(R.id.rowName)
        nameBox.background = Ui.box(this, Ui.ROW, 1f, 0f, Ui.ROW_EDGE, radiiDp = floatArrayOf(10f, 0f, 0f, 10f))
        v.findViewById<TextView>(R.id.rowTitle).text = titleCase(name)
        val versionBox = v.findViewById<TextView>(R.id.rowVersion)
        versionBox.background = Ui.box(this, Ui.VERSION, 1f, 0f, ripple = versions.size > 1)
        val strip = v.findViewById<StepStrip>(R.id.rowStrip)
        strip.cellsAcross = 3f; strip.separators = true
        val row = Row(strip, 0)
        rows.add(row)
        val key = "explore_version_" + versions[0].piece.kind + "_" + name
        var chosen = prefs.getInt(key, 0).coerceIn(0, versions.size - 1)
        fun show(i: Int) {
            chosen = i
            versionBox.text = versions[i].label
            strip.cells = versions[i].cells
            row.centre = Rarity.centre(versions[i].cells, corpus)
            strip.scrollToCell(row.centre, false)
        }
        show(chosen)
        if (versions.size > 1) versionBox.setOnClickListener {
            val m = PopupMenu(this, versionBox)
            for ((i, ver) in versions.withIndex()) m.menu.add(0, i, i, ver.label.replace("\n", ", ")).isCheckable = true
            m.menu.setGroupCheckable(0, true, true)
            m.menu.findItem(chosen)?.isChecked = true
            m.setOnMenuItemClickListener { item -> prefs.edit().putInt(key, item.itemId).apply(); show(item.itemId); true }
            m.show()
        }
        strip.onDragStart = { for (r in rows) if (r !== row) r.strip.scrollToCell(r.centre, true) }
        strip.onTap = { open(versions[chosen].piece) }
        nameBox.setOnClickListener { open(versions[chosen].piece) }
        return v
    }

    /** the dance floor, always on the first step: the start position (user 2026-10-28) */
    private fun open(p: Piece) {
        startActivity(Intent(this, DanceFloorActivity::class.java).putExtra("kind", p.kind).putExtra("name", p.name).putExtra("variant", p.variant))
    }
}
