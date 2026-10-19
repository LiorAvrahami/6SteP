package org.lior.sixstep

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** cm, top view: x to the leader's starting right, y to his starting front; heading in degrees, clockwise from +y. */
data class Placement(val x: Double, val y: Double, val h: Double)

data class Step(
    val count: String, val dancer: Char, val foot: Char, val code: String,
    val x: Double, val y: Double, val heading: Double, val exempt: Boolean, val note: String, val src: String
)

data class Element(
    val name: String, val startState: String, val endState: String, val beats: Int,
    val leaderFreeStart: Char, val source: String, val notes: String,
    val steps: List<Step>, val hands: List<Pair<String, List<String>>>
) {
    /** which foot is free for each dancer at the end */
    fun endFree(dancer: Char): Char {
        val last = steps.last { it.dancer == dancer }
        return if (last.foot == 'L') 'R' else 'L'
    }
    fun baseCount(): Double = steps.minOf { countTime(it.count, 0.0) }
    fun conditionLabel(): String =
        "from ${startState.replace('_', ' ')}, ${if (leaderFreeStart == 'L') "left" else "right"} foot free → ${endState.replace('_', ' ')}"
}

data class DanceState(val name: String, val feet: Map<String, Placement>, val leaderFree: Char, val hands: List<String>, val desc: String)

fun countTime(count: String, swing: Double): Double {
    val c = count.trim()
    val and = c.endsWith("&")
    val n = c.trimEnd('&').trim().toInt()
    return n - 1 + (if (and) swing else 0.0)
}

fun fwd(h: Double): Pair<Double, Double> { val r = Math.toRadians(h); return Pair(sin(r), cos(r)) }
fun right(h: Double): Pair<Double, Double> { val r = Math.toRadians(h); return Pair(cos(r), -sin(r)) }
fun normAngle(a: Double): Double = ((a + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

/** A rigid transform: local skeleton coordinates -> world, defined by the leader's left foot in natural stance. */
class Frame(val x: Double, val y: Double, val h: Double) {
    fun apply(p: Placement): Placement {
        val f = fwd(h); val rt = right(h)
        return Placement(x + p.x * rt.first + p.y * f.first, y + p.x * rt.second + p.y * f.second, p.h + h)
    }
    fun apply(px: Double, py: Double, ph: Double): Placement = apply(Placement(px, py, ph))
}

class DanceData(val states: Map<String, DanceState>, val elements: List<Element>) {
    val names: List<String> = elements.map { it.name }.distinct()
    fun find(name: String, state: String, free: Char): Element? =
        elements.firstOrNull { it.name == name && it.startState == state && it.leaderFreeStart == free }
    fun variants(name: String): List<Element> = elements.filter { it.name == name }

    companion object {
        const val STANCE = 20.0
        fun load(ctx: Context): DanceData = parse(ctx.assets.open("dance/skeletons.json").bufferedReader().readText())

        fun parse(text: String): DanceData {
            val root = JSONObject(text)
            val states = LinkedHashMap<String, DanceState>()
            val sj = root.getJSONObject("states")
            for (key in sj.keys()) {
                val o = sj.getJSONObject(key)
                val feet = LinkedHashMap<String, Placement>()
                val fj = o.getJSONObject("feet")
                for (fk in fj.keys()) {
                    val a = fj.getJSONArray(fk)
                    feet[fk] = Placement(a.getDouble(0), a.getDouble(1), a.getDouble(2))
                }
                states[key] = DanceState(key, feet, o.optString("leader_free", "L").firstOrNull() ?: 'L', strList(o.optJSONArray("hands")), o.optString("desc", ""))
            }
            val elements = ArrayList<Element>()
            val ej = root.getJSONArray("elements")
            for (i in 0 until ej.length()) {
                val e = ej.getJSONObject(i)
                val steps = ArrayList<Step>()
                val stj = e.getJSONArray("steps")
                for (k in 0 until stj.length()) {
                    val s = stj.getJSONObject(k)
                    steps.add(Step(s.getString("count"), s.getString("dancer")[0], s.getString("foot")[0], s.optString("code", ""),
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("heading"), s.optBoolean("exempt", false), s.optString("note", ""), s.optString("src", "")))
                }
                val hands = ArrayList<Pair<String, List<String>>>()
                val hj = e.optJSONArray("hands")
                if (hj != null) for (k in 0 until hj.length()) {
                    val h = hj.getJSONObject(k)
                    hands.add(Pair(h.get("count").toString(), strList(h.optJSONArray("hands"))))
                }
                elements.add(Element(e.getString("name"), e.getString("start_state"), e.getString("end_state"), e.getInt("beats"),
                    e.optString("leader_free_start", "L").firstOrNull() ?: 'L', e.optString("source", ""), e.optString("notes", ""), steps, hands))
            }
            return DanceData(states, elements)
        }

        fun strList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until a.length()) out.add(a.getString(i))
            return out
        }
    }
}

// ------------------------------------------------------------------ compositions

/** An entry of a composition: an element name, a sub-composition name, either followed by " x N", or "say: text" (an annotation).
 *  An entry in parentheses is quiet: danced but never announced, and not a stop for the player's swipes (a bridge between
 *  two elements that do not fit together, or a warm-up). */
data class Entry(val name: String, val times: Int, val say: String?, val quiet: Boolean = false) {
    companion object {
        private val RE = Regex("^(.*?)\\s*(?:[xX×]\\s*(\\d+))?\\s*$")
        fun parse(entry: String): Entry {
            var t = entry.trim()
            if (t.lowercase().startsWith("say:")) return Entry("", 1, t.substring(4).trim())
            val quiet = t.length > 2 && t.startsWith("(") && t.endsWith(")")
            if (quiet) t = t.substring(1, t.length - 1).trim()
            val m = RE.matchEntire(t) ?: return Entry(t, 1, null, quiet)
            val times = m.groupValues[2].toIntOrNull() ?: 1
            return Entry(m.groupValues[1].trim(), maxOf(1, times), null, quiet)
        }
    }
    fun text(): String = if (say != null) "say: $say" else (if (times > 1) "$name x$times" else name).let { if (quiet) "($it)" else it }
}

/** `revision` is bumped in the shipped file when a shipped composition is changed; `edited` marks a copy the user changed on
 *  the phone, which a newer shipped revision then leaves alone (see CompositionStore.load). */
class Composition(
    var name: String, var startState: String, var repeat: Int,
    val elements: MutableList<String>, var notes: String, var isSub: Boolean,
    var revision: Int = 0, var edited: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("start_state", startState).put("repeat", repeat)
        .put("elements", JSONArray(elements)).put("notes", notes).put("revision", revision).put("edited", edited)
    fun copy(): Composition = Composition(name, startState, repeat, elements.toMutableList(), notes, isSub, revision, edited)
    companion object {
        fun fromJson(o: JSONObject, isSub: Boolean): Composition = Composition(
            o.getString("name"), o.optString("start_state", "side"), maxOf(1, o.optInt("repeat", 1)),
            DanceData.strList(o.optJSONArray("elements")).toMutableList(), o.optString("notes", ""), isSub,
            o.optInt("revision", 0), o.optBoolean("edited", false))
    }
}

/** One element of a flat sequence, with where it came from. */
data class SeqItem(val name: String, val group: String?, val annotation: String?, val entryIndex: Int, val entryStart: Boolean, val entryLabel: String)

/** Compositions and sub-compositions: shipped in the assets, edited copies kept in the app's files. */
class CompositionStore(private val ctx: Context) {
    val compositions = ArrayList<Composition>()
    val subs = ArrayList<Composition>()
    private val file: File get() = File(ctx.filesDir, "compositions.json")

    init { load() }

    fun load() {
        compositions.clear(); subs.clear()
        val shipped = JSONObject(ctx.assets.open("dance/compositions.json").bufferedReader().readText())
        val doc = if (file.exists()) JSONObject(file.readText()) else shipped
        readInto(doc)
        // shipped compositions the user's file does not know yet are added, so a new release can bring new ones; a shipped
        // composition with a newer revision replaces the phone's copy unless the user edited that copy (theirs stays)
        if (file.exists()) {
            val sc = shipped.optJSONArray("compositions"); val ss = shipped.optJSONArray("sub_compositions")
            if (sc != null) for (i in 0 until sc.length()) merge(compositions, Composition.fromJson(sc.getJSONObject(i), false))
            if (ss != null) for (i in 0 until ss.length()) merge(subs, Composition.fromJson(ss.getJSONObject(i), true))
        }
    }

    private fun merge(into: ArrayList<Composition>, shipped: Composition) {
        val i = into.indexOfFirst { it.name == shipped.name }
        if (i < 0) into.add(shipped)
        else if (shipped.revision > into[i].revision && !into[i].edited) into[i] = shipped
    }

    private fun readInto(doc: JSONObject) {
        val sc = doc.optJSONArray("compositions"); val ss = doc.optJSONArray("sub_compositions")
        if (sc != null) for (i in 0 until sc.length()) compositions.add(Composition.fromJson(sc.getJSONObject(i), false))
        if (ss != null) for (i in 0 until ss.length()) subs.add(Composition.fromJson(ss.getJSONObject(i), true))
    }

    fun save() {
        val doc = JSONObject()
        doc.put("compositions", JSONArray(compositions.map { it.toJson() }))
        doc.put("sub_compositions", JSONArray(subs.map { it.toJson() }))
        file.writeText(doc.toString(1))
    }

    fun resetToShipped() { if (file.exists()) file.delete(); load() }

    fun find(name: String): Composition? = compositions.firstOrNull { it.name == name }
    fun daily(): Composition? = compositions.firstOrNull { it.name.contains("exercise", ignoreCase = true) } ?: compositions.firstOrNull()

    /** Expand sub-composition names and "x N" repeats into a flat list of elements with their group labels and annotations. */
    fun expand(entries: List<String>, depth: Int = 0, prefix: String? = null): List<SeqItem> {
        val out = ArrayList<SeqItem>()
        val counters = HashMap<String, Int>()
        var pendingSay: String? = null
        entries.forEachIndexed { idx, raw ->
            val e = Entry.parse(raw)
            if (e.say != null) { pendingSay = (pendingSay?.let { "$it. " } ?: "") + e.say; return@forEachIndexed }
            val sub = if (depth < 6) subs.firstOrNull { it.name == e.name } else null
            val label = if (e.times > 1) "${e.name} x${e.times}" else e.name
            val announced = !e.quiet   // a quiet entry has no entry start: nothing announces it and the swipes pass over it
            for (rep in 0 until e.times) {
                if (sub != null) {
                    val n = (counters[e.name] ?: 0) + 1; counters[e.name] = n
                    val group = prefix?.let { "$it / " } .orEmpty() + "${e.name} #$n"
                    val inner = expand(sub.elements, depth + 1, group)
                    inner.forEachIndexed { j, it ->
                        val first = rep == 0 && j == 0
                        out.add(SeqItem(it.name, group, if (first) pendingSay else null, idx, first && announced, label))
                    }
                } else {
                    out.add(SeqItem(e.name, prefix, if (rep == 0) pendingSay else null, idx, rep == 0 && announced, label))
                }
                if (rep == 0) pendingSay = null
            }
        }
        return out
    }

    fun sequence(c: Composition): List<SeqItem> {
        val one = expand(c.elements)
        val out = ArrayList<SeqItem>()
        for (r in 0 until c.repeat) out.addAll(one)
        return out
    }
}

// ------------------------------------------------------------------ composing a sequence into a world timeline

data class CStep(
    val t: Double, val count: String, val dancer: Char, val foot: Char,
    val x: Double, val y: Double, val heading: Double, val element: Int, val hands: List<String>, val filler: Boolean
)

data class CElement(
    val name: String, val start: Double, val beats: Int, val startState: String, val endState: String,
    val free: Char, val endFree: Char, val filler: Boolean, val group: String?, val annotation: String?,
    val entryIndex: Int, val entryStart: Boolean, val entryLabel: String
)

class Composed(
    val steps: List<CStep>, val elements: List<CElement>, val start: Map<String, Placement>, val startHands: List<String>,
    val startState: String, val startFree: Char, val endState: String, val endFree: Char, val length: Double, val errors: List<String>
) {
    val closes: Boolean get() = errors.isEmpty() && endState == startState && endFree == startFree && length > 0
}

object Composer {
    const val FILLER = "triple step"

    fun compose(
        data: DanceData, sequence: List<SeqItem>, startState: String, swing: Double,
        leaderFree: Char? = null, padToLoop: Boolean = false
    ): Composed {
        val errors = ArrayList<String>()
        val state0 = data.states[startState] ?: return Composed(emptyList(), emptyList(), emptyMap(), emptyList(), startState, 'L', startState, 'L', 0.0, listOf("unknown state $startState"))
        var frame = Frame(0.0, 0.0, 0.0)
        var state = startState
        var free = leaderFree ?: state0.leaderFree
        val startFree = free
        var hands: List<String> = state0.hands
        var t = 0.0
        val steps = ArrayList<CStep>()
        val elements = ArrayList<CElement>()

        fun add(elem: Element, item: SeqItem?, filler: Boolean) {
            val idx = elements.size
            elements.add(CElement(elem.name, t, elem.beats, state, elem.endState, elem.leaderFreeStart, elem.endFree('L'), filler,
                item?.group, item?.annotation, item?.entryIndex ?: -1, item?.entryStart ?: false, item?.entryLabel ?: elem.name))
            val base = elem.baseCount()
            val changes = elem.hands.associate { it.first to it.second }
            val seen = HashSet<String>()
            for (s in elem.steps) {
                if (changes.containsKey(s.count) && seen.add(s.count)) hands = changes[s.count]!!
                val w = frame.apply(s.x, s.y, s.heading)
                steps.add(CStep(t + countTime(s.count, swing) - base, s.count, s.dancer, s.foot, w.x, w.y, w.h, idx, hands, filler))
            }
            // the end frame: where the leader's left foot stands in natural stance after his last weighted step
            val last = elem.steps.last { it.dancer == 'L' }
            val lx: Double; val ly: Double
            if (last.foot == 'L') { lx = last.x; ly = last.y } else {
                val r = right(last.heading); lx = last.x - r.first * DanceData.STANCE; ly = last.y - r.second * DanceData.STANCE
            }
            val ef = frame.apply(lx, ly, last.heading)
            frame = Frame(ef.x, ef.y, ef.h)
            free = elem.endFree('L')
            state = elem.endState
            t += elem.beats
        }

        for ((i, item) in sequence.withIndex()) {
            val elem = data.find(item.name, state, free)
            if (elem == null) {
                val other = data.find(item.name, state, if (free == 'L') 'R' else 'L')
                val where = " (element ${i + 1} of ${sequence.size})"
                errors.add(if (other != null) "'${item.name}' from $state needs the leader's ${other.leaderFreeStart} foot free, but his $free is free$where"
                           else "no '${item.name}' starting from $state$where")
                break
            }
            add(elem, item, false)
        }
        if (padToLoop && errors.isEmpty() && sequence.isNotEmpty()) {
            var fillers = 0
            while ((t < 4 || free != startFree) && fillers < 4) {
                val elem = data.find(FILLER, state, free)
                if (elem == null) { errors.add("cannot fill the loop: no $FILLER from $state with the $free foot free"); break }
                add(elem, null, true)
                fillers++
            }
        }
        steps.sortWith(compareBy({ it.t }, { it.dancer }))
        return Composed(steps, elements, state0.feet, state0.hands, startState, startFree, state, free, t, errors)
    }
}
