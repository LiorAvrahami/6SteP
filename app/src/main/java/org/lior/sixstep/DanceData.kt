package org.lior.sixstep

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** cm, top view: x to the leader's starting right, y to his starting front; heading in degrees, clockwise from +y. */
data class Placement(val x: Double, val y: Double, val h: Double)

data class Step(
    val count: String, val dancer: Char, val foot: Char, val code: String,
    val x: Double, val y: Double, val heading: Double, val exempt: Boolean, val note: String, val src: String,
    val via: List<Pair<Double, Double>> = emptyList()   // the anchors of the foot's curved path from its previous place (the user's)
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
    /** every version that starts in this condition, in list order (several may, ending apart: 2026-10-25) */
    fun candidates(name: String, state: String, free: Char): List<Element> =
        elements.filter { it.name == name && it.startState == state && it.leaderFreeStart == free }
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
                    val via = ArrayList<Pair<Double, Double>>()
                    val vj = s.optJSONArray("via")
                    if (vj != null) for (q in 0 until vj.length()) { val a = vj.getJSONArray(q); via.add(Pair(a.getDouble(0), a.getDouble(1))) }
                    steps.add(Step(s.getString("count"), s.getString("dancer")[0], s.getString("foot")[0], s.optString("code", ""),
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("heading"), s.optBoolean("exempt", false), s.optString("note", ""), s.optString("src", ""), via))
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
data class Entry(val name: String, val times: Int, val say: String?, val quiet: Boolean = false, val end: String? = null) {
    companion object {
        private val RE = Regex("^(.*?)\\s*(?:[xX×]\\s*(\\d+))?\\s*$")
        /** the pinned ending: "triple step x2 → facing" (since 2026-10-25; model.parse_entry_full, the editor's parseEntryFull) */
        private val END = Regex("\\s*(?:→|->)\\s*([A-Za-z_]+)\\s*$")
        fun parse(entry: String): Entry {
            var t = entry.trim()
            if (t.lowercase().startsWith("say:")) return Entry("", 1, t.substring(4).trim())
            val quiet = t.length > 2 && t.startsWith("(") && t.endsWith(")")
            if (quiet) t = t.substring(1, t.length - 1).trim()
            var end: String? = null
            val em = END.find(t)
            if (em != null) { end = em.groupValues[1]; t = t.substring(0, em.range.first).trim() }
            val m = RE.matchEntire(t) ?: return Entry(t, 1, null, quiet, end)
            val times = m.groupValues[2].toIntOrNull() ?: 1
            return Entry(m.groupValues[1].trim(), maxOf(1, times), null, quiet, end)
        }
    }
    fun text(): String = if (say != null) "say: $say" else ((if (times > 1) "$name x$times" else name) + (if (end != null) " → $end" else "")).let { if (quiet) "($it)" else it }
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
data class SeqItem(val name: String, val group: String?, val annotation: String?, val entryIndex: Int, val entryStart: Boolean, val entryLabel: String,
                   val end: String? = null)   // the pinned ending, or null: the composer chooses

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

    /** Expand sub-composition names and "x N" repeats into a flat list of elements with their group labels and annotations.
     *  Inside a sub-composition its own name (or that of one it is inside) means the element of that name (2026-10-25). */
    fun expand(entries: List<String>, depth: Int = 0, prefix: String? = null, inside: Set<String> = emptySet()): List<SeqItem> {
        val out = ArrayList<SeqItem>()
        val counters = HashMap<String, Int>()
        var pendingSay: String? = null
        entries.forEachIndexed { idx, raw ->
            val e = Entry.parse(raw)
            if (e.say != null) { pendingSay = (pendingSay?.let { "$it. " } ?: "") + e.say; return@forEachIndexed }
            val sub = if (depth < 6 && e.name !in inside) subs.firstOrNull { it.name == e.name } else null
            val label = if (e.times > 1) "${e.name} x${e.times}" else e.name
            val announced = !e.quiet   // a quiet entry has no entry start: nothing announces it and the swipes pass over it
            for (rep in 0 until e.times) {
                if (sub != null) {
                    val n = (counters[e.name] ?: 0) + 1; counters[e.name] = n
                    val group = prefix?.let { "$it / " } .orEmpty() + "${e.name} #$n"
                    val inner = expand(sub.elements, depth + 1, group, inside + e.name)
                    inner.forEachIndexed { j, it ->
                        val first = rep == 0 && j == 0
                        out.add(SeqItem(it.name, group, if (first) pendingSay else null, idx, first && announced, label, it.end))
                    }
                } else {
                    out.add(SeqItem(e.name, prefix, if (rep == 0) pendingSay else null, idx, rep == 0 && announced, label, e.end))
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
    val x: Double, val y: Double, val heading: Double, val element: Int, val hands: List<String>, val filler: Boolean,
    val via: List<Pair<Double, Double>> = emptyList()
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

    /** How an element joins the dance (the user's rule, 2026-10-26; model.couple_frame): its start position is turned and
     *  moved so that it has the same anchor point (the middle between his and her weight-bearing feet) and the same direction
     *  from the leader to the follower (the middle of his feet to the middle of hers) as where the dancers really are.
     *  Weighted feet do not move at the join. With no direction (both on one spot) the fallback frame is kept. */
    fun coupleFrame(start: Map<String, Placement>, real: Map<String, Placement>, fallback: Frame, weighted: List<String>): Frame {
        val keys = listOf("L.L", "L.R", "F.L", "F.R")
        if (keys.any { start[it] == null || real[it] == null }) return fallback
        fun mid(m: Map<String, Placement>, d: Char) = Pair((m["$d.L"]!!.x + m["$d.R"]!!.x) / 2, (m["$d.L"]!!.y + m["$d.R"]!!.y) / 2)
        val w = if (weighted.all { it in keys }) weighted else keys
        val c0x = w.sumOf { start[it]!!.x } / w.size; val c0y = w.sumOf { start[it]!!.y } / w.size
        val c1x = w.sumOf { real[it]!!.x } / w.size; val c1y = w.sumOf { real[it]!!.y } / w.size
        val l0 = mid(start, 'L'); val f0 = mid(start, 'F'); val l1 = mid(real, 'L'); val f1 = mid(real, 'F')
        if (hypot(f0.first - l0.first, f0.second - l0.second) <= 1.0 || hypot(f1.first - l1.first, f1.second - l1.second) <= 1.0) return fallback
        val h = Math.toDegrees(atan2(f1.first - l1.first, f1.second - l1.second)) - Math.toDegrees(atan2(f0.first - l0.first, f0.second - l0.second))
        val rc = Frame(0.0, 0.0, h).apply(c0x, c0y, 0.0)
        return Frame(c1x - rc.x, c1y - rc.y, h)
    }

    fun compose(
        data: DanceData, sequence: List<SeqItem>, startState: String, swing: Double,
        leaderFree: Char? = null, padToLoop: Boolean = false
    ): Composed {
        val errors = ArrayList<String>()
        val state0 = data.states[startState] ?: return Composed(emptyList(), emptyList(), emptyMap(), emptyList(), startState, 'L', startState, 'L', 0.0, listOf("unknown state $startState"))
        var frame = Frame(0.0, 0.0, 0.0)
        // each foot's last world heading, winding included (headings are not wrapped since 2026-10-25)
        val lastH = HashMap<String, Double>()
        for ((k, p) in state0.feet) lastH[k] = p.h
        val cur = HashMap<String, Placement>(state0.feet)   // where every foot is in the world
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
            // the element's headings assume its start state's: a foot that arrives wound by a full turn keeps it
            val stFeet = data.states[state]?.feet ?: state0.feet
            // his and her weight-bearing foot: his is the one not free, hers is the one on his free foot's side (she is free on the other)
            val weighted = listOf("L.${if (free == 'L') 'R' else 'L'}", "F.$free")
            frame = coupleFrame(stFeet, cur, frame, weighted)   // the join: the middle of the weighted feet and his-to-her direction stay
            val wind = HashMap<String, Double>()
            for ((k, h) in lastH) {
                val nominal = frame.h + (stFeet[k]?.h ?: 0.0)
                wind[k] = 360.0 * round((h - nominal) / 360.0)
            }
            for (s in elem.steps) {
                if (changes.containsKey(s.count) && seen.add(s.count)) hands = changes[s.count]!!
                val w = frame.apply(s.x, s.y, s.heading)
                val key = "${s.dancer}.${s.foot}"
                val wh = w.h + (wind[key] ?: 0.0)
                lastH[key] = wh
                cur[key] = Placement(w.x, w.y, wh)
                val via = s.via.map { v -> val q = frame.apply(v.first, v.second, 0.0); Pair(q.x, q.y) }
                steps.add(CStep(t + countTime(s.count, swing) - base, s.count, s.dancer, s.foot, w.x, w.y, wh, idx, hands, filler, via))
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

        // which version of each element (several may start alike and end apart, 2026-10-25): from the back, the conditions
        // from which the rest can still be danced; from the front, the first version that honours the pin and keeps the rest
        // possible (model.compose does the same)
        val conds = data.states.keys.flatMap { st -> listOf(Pair(st, 'L'), Pair(st, 'R')) }
        val feas = arrayOfNulls<Set<Pair<String, Char>>>(sequence.size + 1)   // null: anything may follow
        for (i in sequence.indices.reversed()) {
            val ok = HashSet<Pair<String, Char>>()
            for (cd in conds) {
                val pin = sequence[i].end
                val any = data.candidates(sequence[i].name, cd.first, cd.second).any { c ->
                    (pin == null || c.endState == pin) && (feas[i + 1]?.contains(Pair(c.endState, c.endFree('L'))) ?: true)
                }
                if (any) ok.add(cd)
            }
            feas[i] = ok
        }
        for ((i, item) in sequence.withIndex()) {
            val cs = data.candidates(item.name, state, free)
            val csP = if (item.end != null) cs.filter { it.endState == item.end } else cs
            if (cs.isNotEmpty() && csP.isEmpty()) { errors.add("no '${item.name}' from $state ending in ${item.end} (element ${i + 1} of ${sequence.size})"); break }
            val elem = csP.firstOrNull { c -> feas[i + 1]?.contains(Pair(c.endState, c.endFree('L'))) ?: true } ?: csP.firstOrNull()
            if (elem == null) {
                val other = data.find(item.name, state, if (free == 'L') 'R' else 'L')
                val where = " (element ${i + 1} of ${sequence.size})"
                errors.add(if (other != null) "'${item.name}' from $state needs the leader's ${other.leaderFreeStart} foot free, but his $free is free$where"
                           else "no '${item.name}' starting from $state$where")
                break
            }
            add(elem, item, false)
        }
        // pad only a piece that ends where it started; one that ends elsewhere restarts from its start, unpadded (user 2026-10-25)
        if (padToLoop && errors.isEmpty() && sequence.isNotEmpty() && state == startState) {
            var fillers = 0
            // only until the leader's free foot is back (user 2026-10-19: a rock step played alone stays a rock step)
            while (free != startFree && fillers < 4) {
                val cs = data.candidates(FILLER, state, free)
                val elem = cs.firstOrNull { it.endState == state } ?: cs.firstOrNull()   // one that stays in this state, so the loop closes
                if (elem == null) break   // no filler for this condition: it plays unpadded, not an error
                add(elem, null, true)
                fillers++
            }
        }
        steps.sortWith(compareBy({ it.t }, { it.dancer }))
        return Composed(steps, elements, state0.feet, state0.hands, startState, startFree, state, free, t, errors)
    }
}
