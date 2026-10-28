package org.lior.sixstep

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat

/** Explore (the user's layout draft, 2026-10-28): three groups that fold. Elements: the user's own list, in their order,
 *  whether each is an element or a building block underneath (2026-10-29); Compositions, with the quick recap; Building
 *  Blocks: everything else. Each row: the name, its versions (the starting conditions of an element) as pills under it
 *  when they all fit, otherwise a raised button at the right of the name with a count badge that opens a list (the
 *  user's choice of the two picker options, 2026-10-29); a piece with one version shows where it starts and ends as
 *  plain text. Then three pictures of consecutive steps, centred on the step that stands out most (Rarity); drag a row
 *  sideways to move through its steps, which puts the others back where they began. A tap opens the dance floor on the
 *  first step of the version shown; the version chosen is remembered. */
class ExploreActivity : AppCompatActivity() {
    companion object {
        /** the Elements group, in this order (user 2026-10-29); every other element and block is under Building Blocks */
        val ELEMENTS = listOf("rock step", "rock triple triple", "triple step", "side to side", "follower underarm and back",
            "follower underarm pass", "rock back cutoff", "lead underarm pass", "circle", "follower underarm to arrow")
    }

    /** one way to dance a row's piece: an element from one starting condition, or the one way of a block or composition */
    private class Version(val piece: Piece, val facing: String, val foot: String, val footShort: String, val cells: List<StepStrip.Cell>)
    private class Row(val strip: StepStrip, var centre: Int)
    private val rows = ArrayList<Row>()
    private var corpus: List<DoubleArray> = emptyList()
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Float): Int = (v * d).toInt()

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
            val left = c.startFree == 'L'
            return Version(p, c.startState.replace('_', ' ') + " → " + c.endState.replace('_', ' '),
                getString(if (left) R.string.left_free else R.string.right_free),
                getString(if (left) R.string.left_free_short else R.string.right_free_short), cellsOf(t, stopsOf(t), start))
        }
        val elementRows = data.names.associateWith { name ->
            data.variants(name).indices.mapNotNull { i -> Piece.find(data, store, Piece.ELEMENT, name, i)?.let { version(it) } }
        }.filterValues { it.isNotEmpty() }
        val blockRows = store.subs.associate { c -> c.name to listOfNotNull(Piece.find(data, store, Piece.BLOCK, c.name)?.let { version(it) }) }
            .filterValues { it.isNotEmpty() }
        // the poses of every version of every element: what usual looks like (the start positions aside)
        corpus = elementRows.values.flatMap { vs -> vs.flatMap { v -> v.cells.drop(1).mapNotNull { Rarity.descriptor(it.feet) } } }
        fun rowFor(name: String): Pair<String, List<Version>>? =
            (elementRows.entries.firstOrNull { it.key.equals(name, true) } ?: blockRows.entries.firstOrNull { it.key.equals(name, true) })?.toPair()
        val elements = ELEMENTS.mapNotNull { rowFor(it) }
        val shown = elements.map { it.first }.toSet()
        val blocks = (elementRows + blockRows).filterKeys { it !in shown }.toList()
        val compositions = store.compositions.mapNotNull { c -> Piece.find(data, store, Piece.COMPOSITION, c.name)?.let { version(it) }?.let { Pair(c.name, listOf(it)) } }
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
        val column = v.findViewById<View>(R.id.rowColumn)
        val facing = v.findViewById<TextView>(R.id.rowFacing)
        val pills = v.findViewById<LinearLayout>(R.id.rowPills)
        val chip = v.findViewById<View>(R.id.rowChip)
        val chipBox = v.findViewById<View>(R.id.rowChipBox)
        val chipText = v.findViewById<TextView>(R.id.rowChipText)
        val strip = v.findViewById<StepStrip>(R.id.rowStrip)
        strip.cellsAcross = 3f; strip.separators = true
        val row = Row(strip, 0)
        rows.add(row)
        val key = "explore_version_" + versions[0].piece.kind + "_" + name
        var chosen = prefs.getInt(key, 0).coerceIn(0, versions.size - 1)
        val pillViews = ArrayList<TextView>()

        fun show(i: Int) {
            chosen = i
            val ver = versions[i]
            chipText.text = ver.facing + "\n" + ver.foot
            for ((k, p) in pillViews.withIndex()) stylePill(p, k == i)
            strip.cells = ver.cells
            row.centre = Rarity.centre(ver.cells, corpus)
            strip.scrollToCell(row.centre, false)
        }
        fun pick(i: Int) { prefs.edit().putInt(key, i).apply(); show(i) }

        if (versions.size == 1) {
            facing.text = versions[0].facing + " · " + versions[0].foot
            facing.visibility = View.VISIBLE; pills.visibility = View.GONE
        } else {
            for ((i, ver) in versions.withIndex()) {
                val label = SpannableString(ver.facing + "\n" + ver.footShort)
                label.setSpan(RelativeSizeSpan(0.875f), ver.facing.length + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val p = TextView(this)
                p.text = label; p.textSize = 12f; p.typeface = ResourcesCompat.getFont(this, R.font.inter_extrabold)
                p.gravity = Gravity.CENTER_VERTICAL; p.minHeight = dp(44f); p.setPadding(dp(11f), 0, dp(12f), 0)
                p.setLineSpacing(0f, 0.95f); p.compoundDrawablePadding = dp(5f); p.isClickable = true
                p.setOnClickListener { pick(i) }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.marginStart = dp(6f)
                pills.addView(p, lp); pillViews.add(p)
            }
            chipBox.background = Ui.raised(this, Ui.VERSION)
            chipBox.contentDescription = getString(R.string.choose_version)
            v.findViewById<TextView>(R.id.rowChipCount).let { c -> c.text = versions.size.toString(); c.background = Ui.box(this, Color.BLACK, 0f, 11f, ripple = false) }
            chipBox.setOnClickListener { showList(chipBox, versions, chosen) { pick(it) } }
            // the pills when they all fit beside the strip, else the button (decided once the row has its width)
            column.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (right - left <= 0) return
                    view.removeOnLayoutChangeListener(this)
                    pills.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    val fits = pills.measuredWidth <= right - left
                    view.post {
                        if (fits) pills.visibility = View.VISIBLE
                        else { pills.visibility = View.GONE; chip.visibility = View.VISIBLE }
                    }
                }
            })
        }
        show(chosen)
        strip.onDragStart = { for (r in rows) if (r !== row) r.strip.scrollToCell(r.centre, false) }   // at once, not gliding (user 2026-10-29)
        strip.onTap = { open(versions[chosen].piece) }
        nameBox.setOnClickListener { open(versions[chosen].piece) }
        return v
    }

    /** a version pill: black with a tick when it is the one shown, outlined otherwise */
    private fun stylePill(p: TextView, selected: Boolean) {
        p.background = Ui.box(this, if (selected) Color.BLACK else Color.TRANSPARENT, 1.5f, 22f)
        p.setTextColor(if (selected) Color.WHITE else Color.BLACK)
        val tick = if (!selected) null else AppCompatResources.getDrawable(this, R.drawable.ic_check)?.mutate()?.also {
            DrawableCompat.setTint(it, Color.WHITE); it.setBounds(0, 0, dp(14f), dp(14f))
        }
        p.setCompoundDrawablesRelative(tick, null, null, null)
    }

    /** the versions as a list under the raised button: the one shown tinted and bold, a tap on another chooses it */
    private fun showList(anchor: View, versions: List<Version>, chosen: Int, pick: (Int) -> Unit) {
        val width = dp(300f)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(6f), dp(6f), dp(6f) + dp(4f), dp(6f) + dp(4f))
        box.background = Ui.raised(this, Color.WHITE, 14f, 4f, ripple = false)
        val popup = PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for ((i, ver) in versions.withIndex()) {
            val t = TextView(this)
            t.text = ver.facing + " · " + ver.foot
            t.textSize = 14f; t.setTextColor(Color.BLACK)
            t.typeface = ResourcesCompat.getFont(this, if (i == chosen) R.font.inter_extrabold else R.font.inter_semibold)
            t.gravity = Gravity.CENTER_VERTICAL; t.minHeight = dp(48f); t.setPadding(dp(14f), 0, dp(14f), 0)
            t.background = Ui.box(this, if (i == chosen) Ui.VERSION else Color.WHITE, 0f, 9f)
            t.setOnClickListener { popup.dismiss(); pick(i) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = dp(4f)
            box.addView(t, lp)
        }
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.showAsDropDown(anchor, anchor.width - width, dp(6f))
    }

    /** the dance floor, always on the first step: the start position (user 2026-10-28) */
    private fun open(p: Piece) {
        startActivity(Intent(this, DanceFloorActivity::class.java).putExtra("kind", p.kind).putExtra("name", p.name).putExtra("variant", p.variant))
    }
}
