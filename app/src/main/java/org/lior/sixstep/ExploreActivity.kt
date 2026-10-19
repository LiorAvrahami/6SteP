package org.lior.sixstep

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Elements, sub-compositions and compositions: tap to explore on the floor, long-press a composition for the pencil.
 *  Anything with several versions for different starting conditions is a folding row: tap to open the versions, tap again to fold. */
class ExploreActivity : AppCompatActivity() {
    private lateinit var data: DanceData
    private lateinit var store: CompositionStore
    private lateinit var list: LinearLayout
    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_explore)
        data = DanceData.load(this); store = CompositionStore(this)
        list = findViewById(R.id.list)
        findViewById<MaterialButton>(R.id.newComposition).setOnClickListener {
            var k = store.compositions.size + 1; var nm = "composition $k"
            while (store.compositions.any { it.name == nm }) nm = "composition ${++k}"
            val c = Composition(nm, "side", 1, ArrayList(), "", false)
            editDialog(c, isNew = true)
        }
    }

    // the store is loaded once in onCreate; nothing else writes it while this screen is up, and reloading here would
    // orphan a composition held by an open edit dialog (review finding)
    override fun onResume() { super.onResume(); rebuild() }

    private fun header(text: String) {
        val tv = TextView(this); tv.text = text; tv.textSize = 20f; tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(16, 36, 16, 8); list.addView(tv)
    }

    private fun row(title: String, subtitle: String?, indent: Int = 0, onTap: () -> Unit, onEdit: (() -> Unit)? = null) {
        val ll = LinearLayout(this); ll.orientation = LinearLayout.HORIZONTAL; ll.gravity = Gravity.CENTER_VERTICAL
        ll.setPadding(24 + indent, 18, 24, 18); ll.isClickable = true; ll.setBackgroundResource(android.R.drawable.list_selector_background)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val t = TextView(this); t.text = title; t.textSize = if (indent > 0) 15f else 17f; t.setTextColor(getColor(R.color.ink)); col.addView(t)
        if (subtitle != null) { val s = TextView(this); s.text = subtitle; s.textSize = 13f; s.setTextColor(getColor(R.color.muted)); col.addView(s) }
        ll.addView(col)
        var pencil: ImageButton? = null
        if (onEdit != null) {
            val b = ImageButton(this); b.setImageResource(R.drawable.ic_edit); b.setBackgroundResource(android.R.drawable.btn_default_small)
            b.visibility = View.GONE; b.contentDescription = getString(R.string.edit); b.setOnClickListener { onEdit() }
            ll.addView(b); pencil = b
            ll.setOnLongClickListener { b.visibility = if (b.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true }
        }
        ll.setOnClickListener { pencil?.visibility = View.GONE; onTap() }
        list.addView(ll)
    }

    private fun conditionLabel(state: String, free: Char) = "from ${state.replace('_', ' ')}, ${if (free == 'L') "left" else "right"} foot free"

    /** the starting conditions a sub-composition can be danced from */
    private fun subConditions(c: Composition): List<Pair<String, Char>> {
        val out = ArrayList<Pair<String, Char>>()
        val seq = store.sequence(c)
        for (state in data.states.keys) for (free in listOf('L', 'R')) {
            val r = Composer.compose(data, seq, state, 0.67, free)
            if (r.errors.isEmpty() && r.elements.isNotEmpty()) out.add(Pair(state, free))
        }
        return out
    }

    private fun openFloor(kind: String, name: String, variant: Int = 0, state: String? = null, free: Char? = null) {
        val i = Intent(this, FloorActivity::class.java).putExtra("kind", kind).putExtra("name", name).putExtra("variant", variant)
        if (state != null) i.putExtra("state", state)
        if (free != null) i.putExtra("free", free.toString())
        startActivity(i)
    }

    private fun rebuild() {
        list.removeAllViews()
        header(getString(R.string.elements))
        for (name in data.names) {
            val vs = data.variants(name)
            if (vs.size == 1) {
                val v = vs[0]
                row(name, "${v.beats} beats · " + v.conditionLabel(), 0, { openFloor("element", name, 0) })
            } else {
                val open = name in expanded
                row((if (open) "▾  " else "▸  ") + name, "${vs.size} versions, by starting condition", 0, { if (open) expanded.remove(name) else expanded.add(name); rebuild() })
                if (open) for ((i, v) in vs.withIndex()) {
                    row(v.conditionLabel(), "${v.beats} beats", 48, { openFloor("element", name, i) })
                }
            }
        }
        header(getString(R.string.sub_compositions))
        for (c in store.subs) {
            val conds = subConditions(c)
            val key = "sub:" + c.name
            if (conds.size <= 1) {
                row(c.name, c.elements.joinToString(", "), 0, { openFloor("sub", c.name, 0, conds.firstOrNull()?.first, conds.firstOrNull()?.second) }, { editDialog(c, false) })
            } else {
                val open = key in expanded
                row((if (open) "▾  " else "▸  ") + c.name, c.elements.joinToString(", ") + "  · ${conds.size} starting conditions", 0,
                    { if (open) expanded.remove(key) else expanded.add(key); rebuild() }, { editDialog(c, false) })
                if (open) for ((state, free) in conds) {
                    row(conditionLabel(state, free), null, 48, { openFloor("sub", c.name, 0, state, free) })
                }
            }
        }
        header(getString(R.string.compositions))
        for (c in store.compositions) row(c.name, c.elements.joinToString(", ") + (if (c.repeat > 1) "   (x${c.repeat})" else ""), 0,
            { openFloor("composition", c.name) }, { editDialog(c, false) })
    }

    /** The edit dialog: name, start, repeat, the entries with move/remove, and an add row (element, sub-composition or annotation). */
    private fun editDialog(c: Composition, isNew: Boolean) {
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setPadding(40, 20, 40, 10)
        val name = EditText(this); name.hint = "name"; name.setText(c.name); root.addView(name)
        val line = LinearLayout(this); line.orientation = LinearLayout.HORIZONTAL; line.gravity = Gravity.CENTER_VERTICAL
        val startLabel = TextView(this); startLabel.text = "from "; line.addView(startLabel)
        val states = data.states.keys.toList()
        val start = Spinner(this); start.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, states)
        start.setSelection(maxOf(0, states.indexOf(c.startState))); line.addView(start)
        val repLabel = TextView(this); repLabel.text = "   repeat x"; line.addView(repLabel)
        val repeat = EditText(this); repeat.inputType = InputType.TYPE_CLASS_NUMBER; repeat.setText(c.repeat.toString()); repeat.minWidth = 120; line.addView(repeat)
        val subLabel = TextView(this); subLabel.text = if (c.isSub) "   sub-composition" else ""; line.addView(subLabel)
        root.addView(line)
        val entries = c.elements.toMutableList()
        val entryBox = LinearLayout(this); entryBox.orientation = LinearLayout.VERTICAL; root.addView(entryBox)
        fun renderEntries() {
            entryBox.removeAllViews()
            entries.forEachIndexed { i, e ->
                val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
                val t = TextView(this); t.text = "${i + 1}. $e"; t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); r.addView(t)
                val up = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle); up.text = "▲"; up.setOnClickListener { if (i > 0) { entries.add(i - 1, entries.removeAt(i)); renderEntries() } }; r.addView(up)
                val dn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle); dn.text = "▼"; dn.setOnClickListener { if (i < entries.size - 1) { entries.add(i + 1, entries.removeAt(i)); renderEntries() } }; r.addView(dn)
                val rm = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle); rm.text = "✕"; rm.setOnClickListener { entries.removeAt(i); renderEntries() }; r.addView(rm)
                entryBox.addView(r)
            }
        }
        renderEntries()
        val addLine = LinearLayout(this); addLine.orientation = LinearLayout.HORIZONTAL; addLine.gravity = Gravity.CENTER_VERTICAL
        val choices = ArrayList<String>(); choices.addAll(data.names); choices.addAll(store.subs.filter { it.name != c.name }.map { "sub: " + it.name }); choices.add("annotation (text that is shown and read aloud)")
        val pick = Spinner(this); pick.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        pick.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); addLine.addView(pick)
        val timesLabel = TextView(this); timesLabel.text = " x"; addLine.addView(timesLabel)
        val times = EditText(this); times.inputType = InputType.TYPE_CLASS_NUMBER; times.setText("1"); times.minWidth = 110; addLine.addView(times)
        val quiet = CheckBox(this); quiet.text = getString(R.string.quiet); addLine.addView(quiet)
        val add = MaterialButton(this); add.text = "add"; addLine.addView(add)
        root.addView(addLine)
        val sayText = EditText(this); sayText.hint = "annotation text"; sayText.visibility = View.GONE; root.addView(sayText)
        pick.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { sayText.visibility = if (pos == choices.size - 1) View.VISIBLE else View.GONE }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        add.setOnClickListener {
            val pos = pick.selectedItemPosition
            if (pos == choices.size - 1) { val s = sayText.text.toString().trim(); if (s.isNotEmpty()) { entries.add("say: $s"); sayText.setText("") } }
            else { val nm = choices[pos].removePrefix("sub: "); val n = times.text.toString().toIntOrNull() ?: 1; entries.add(Entry(nm, n, null, quiet.isChecked).text()) }
            renderEntries()
        }
        val scroll = ScrollView(this); scroll.addView(root)
        val originalName = c.name
        fun target(): Composition = (if (c.isSub) store.subs.firstOrNull { it.name == originalName } else store.find(originalName)) ?: c
        val b = AlertDialog.Builder(this).setTitle(if (isNew) getString(R.string.new_composition) else getString(R.string.edit)).setView(scroll)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = if (isNew) c else target()
                t.name = name.text.toString().trim().ifEmpty { t.name }; t.startState = states[start.selectedItemPosition]
                t.repeat = maxOf(1, repeat.text.toString().toIntOrNull() ?: 1)
                t.elements.clear(); t.elements.addAll(entries); t.edited = true
                if (isNew) store.compositions.add(t)
                store.save(); rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
        if (!isNew) b.setNeutralButton(R.string.delete) { _, _ ->
            val t = target()
            if (t.isSub) store.subs.remove(t) else store.compositions.remove(t)
            store.save(); rebuild()
        }
        b.show()
    }
}
