package org.lior.sixstep

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A foot's curved path through the anchors the user set in the editor: a Catmull-Rom spline through the points (start,
 *  anchors, landing), the ends duplicated, walked by length so the foot moves evenly. The same algorithm as model.curve_at
 *  (Python) and curveAt (the editor). */
object Curve {
    const val SEG = 16
    fun samples(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val n = pts.size
        if (n <= 2) return pts
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until n - 1) {
            val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, n - 1)]
            for (k in (if (i == 0) 0 else 1)..SEG) {
                val t = k.toDouble() / SEG
                fun cr(a0: Double, a1: Double, a2: Double, a3: Double) = 0.5 * (2 * a1 + (-a0 + a2) * t + (2 * a0 - 5 * a1 + 4 * a2 - a3) * t * t + (-a0 + 3 * a1 - 3 * a2 + a3) * t * t * t)
                out.add(Pair(cr(p0.first, p1.first, p2.first, p3.first), cr(p0.second, p1.second, p2.second, p3.second)))
            }
        }
        return out
    }
    fun at(pts: List<Pair<Double, Double>>, f: Double): Pair<Double, Double> {
        if (pts.size == 2) return Pair(pts[0].first + (pts[1].first - pts[0].first) * f, pts[0].second + (pts[1].second - pts[0].second) * f)
        val s = samples(pts)
        val seg = DoubleArray(s.size - 1) { k -> hypot(s[k + 1].first - s[k].first, s[k + 1].second - s[k].second) }
        val total = seg.sum()
        if (total <= 1e-9) return s.last()
        val want = f.coerceIn(0.0, 1.0) * total; var run = 0.0
        for (k in seg.indices) {
            if (run + seg[k] >= want) {
                val u = if (seg[k] > 1e-12) (want - run) / seg[k] else 0.0
                return Pair(s[k].first + (s[k + 1].first - s[k].first) * u, s[k].second + (s[k + 1].second - s[k].second) * u)
            }
            run += seg[k]
        }
        return s.last()
    }
}

/** Where every foot is and how much weight it carries at any beat: a foot lands on its count, its weight ramps up over
 *  the next 0.3 beat while the other foot's ramps down, and a foot travels only while its weight is zero.
 *  Before the first step a dancer stands in the neutral stance with the weight on both feet; the weight moves onto the foot
 *  that stays over SHIFT beats, ending as the first foot leaves (user 2026-10-19). */
class Timeline(val composed: Composed, val swing: Double) {
    companion object {
        const val RAMP = 0.3
        const val RAMP_FRACTION = 0.45
        const val SHIFT = 0.3
        fun smoothstep(u0: Double): Double { val u = max(0.0, min(1.0, u0)); return u * u * (3 - 2 * u) }
    }

    class Landing(val tLand: Double, val tRelease: Double, val p: Placement, val via: List<Pair<Double, Double>> = emptyList())
    class FootTrack(start: Placement) {
        val landings = ArrayList<Landing>().apply { add(Landing(-1e9, -1e9, start)) }
        private var cursor = 0
        private fun indexAt(t: Double): Int {
            // landings are sorted by time; start from the last answer, which is usually right or one off
            var i = cursor.coerceIn(0, landings.size - 1)
            while (i > 0 && landings[i].tLand > t) i--
            while (i + 1 < landings.size && landings[i + 1].tLand <= t) i++
            cursor = i
            return i
        }
        fun placementAt(t: Double): Placement {
            val i = indexAt(t)
            val cur = landings[i]
            if (i + 1 < landings.size) {
                val nx = landings[i + 1]
                if (t >= nx.tRelease && t < nx.tLand) {
                    val u = smoothstep((t - nx.tRelease) / max(1e-6, nx.tLand - nx.tRelease))
                    val dh = nx.p.h - cur.p.h   // the raw difference: the stored winding chooses the way round
                    if (nx.via.isEmpty()) return Placement(cur.p.x + (nx.p.x - cur.p.x) * u, cur.p.y + (nx.p.y - cur.p.y) * u, cur.p.h + dh * u)
                    val pts = ArrayList<Pair<Double, Double>>(); pts.add(Pair(cur.p.x, cur.p.y)); pts.addAll(nx.via); pts.add(Pair(nx.p.x, nx.p.y))
                    val q = Curve.at(pts, u)
                    return Placement(q.first, q.second, cur.p.h + dh * u)
                }
            }
            return cur.p
        }
        /** the landing in force at beat t (the last one at or before t) and the one after it */
        fun around(t: Double): Pair<Landing, Landing?> {
            val i = indexAt(t)
            return Pair(landings[i], landings.getOrNull(i + 1))
        }
    }
    /** w0: the foot that carries the weight when the first step leaves (the other one steps first); shiftEnd: that moment */
    class DancerTracks(val tracks: Map<Char, FootTrack>, val ramps: List<Triple<Double, Double, Char>>, val w0: Char, val shiftEnd: Double)

    val dancers: Map<Char, DancerTracks>
    val length: Double get() = composed.length
    private val elementStarts: DoubleArray = composed.elements.map { it.start }.toDoubleArray()
    private val firstStep: Double = composed.steps.minOfOrNull { it.t } ?: 0.0

    init {
        val m = HashMap<Char, DancerTracks>()
        for (d in listOf('L', 'F')) {
            val ds = composed.steps.filter { it.dancer == d }
            val tr = mapOf('L' to FootTrack(composed.start["$d.L"] ?: Placement(0.0, 0.0, 0.0)), 'R' to FootTrack(composed.start["$d.R"] ?: Placement(20.0, 0.0, 0.0)))
            val ramps = ds.mapIndexed { i, s ->
                val nxt = if (i + 1 < ds.size) ds[i + 1].t else s.t + 2.0
                Triple(s.t, min(RAMP, RAMP_FRACTION * (nxt - s.t)), s.foot)
            }
            ds.forEachIndexed { i, s ->
                var release = if (i == 0) s.t - 0.6 else ramps[i - 1].first + ramps[i - 1].second
                val track = tr[s.foot]!!
                val prev = track.landings.last().p
                if (hypot(prev.x - s.x, prev.y - s.y) < 0.5 && abs(prev.h - s.heading) < 0.5) release = s.t
                track.landings.add(Landing(s.t, min(release, s.t), Placement(s.x, s.y, s.heading), s.via))
            }
            val first = ds.firstOrNull()
            val w0 = if (first == null) (if (d == 'L') 'R' else 'L') else if (first.foot == 'L') 'R' else 'L'
            val shiftEnd = if (first == null) -1e9 else tr[first.foot]!!.landings[1].tRelease
            m[d] = DancerTracks(tr, ramps, w0, shiftEnd)
        }
        dancers = m
    }

    fun placement(d: Char, foot: Char, t: Double): Placement = dancers[d]!!.tracks[foot]!!.placementAt(t)

    fun weights(d: Char, t: Double): Map<Char, Double> {
        val dt = dancers[d]!!
        val w = hashMapOf('L' to 0.0, 'R' to 0.0); w[dt.w0] = 1.0
        if (t < dt.shiftEnd) {
            val u = smoothstep((t - (dt.shiftEnd - SHIFT)) / SHIFT); val other = if (dt.w0 == 'L') 'R' else 'L'
            w[dt.w0] = 0.5 + 0.5 * u; w[other] = 0.5 - 0.5 * u
            return w
        }
        for ((tl, ramp, foot) in dt.ramps) {
            if (t < tl) break
            val u = smoothstep((t - tl) / max(1e-6, ramp))
            val other = if (foot == 'L') 'R' else 'L'
            if (u >= 1) { w[foot] = 1.0; w[other] = 0.0 } else { w[foot] = max(w[foot]!!, u); w[other] = min(w[other]!!, 1 - u) }
        }
        return w
    }

    /** the instant just after the weight has settled on whatever landed at t (the longest ramp of the landings at t) */
    fun settledAfter(t: Double): Double {
        if (t < firstStep - 1e-6) return t   // the neutral stance before the first step: shown as it is
        var r = 0.0
        for (d in dancers.values) for ((tl, ramp, _) in d.ramps) if (abs(tl - t) < 1e-6) r = max(r, ramp)
        return t + (if (r > 0) r else RAMP) + 1e-6
    }

    fun handsAt(t: Double): List<String> {
        var h = composed.startHands
        for (s in composed.steps) { if (s.t <= t) h = s.hands else break }
        return h
    }

    fun elementIndexAt(t: Double): Int {
        var i = -1
        for (k in elementStarts.indices) if (elementStarts[k] <= t) i = k else break
        if (i < 0) return -1
        val e = composed.elements[i]
        return if (t < e.start + e.beats) i else -1
    }
    fun elementAt(t: Double): CElement? { val i = elementIndexAt(t); return if (i < 0) null else composed.elements[i] }

    /** The musical count being danced: the element's own numbering; "&" only where a step exists. */
    fun countLabel(t: Double): String {
        val k = elementIndexAt(t); if (k < 0) return ""
        val e = composed.elements[k]
        val counts = composed.steps.filter { it.element == k }.map { it.count }
        if (counts.isEmpty()) return ""
        val base = counts.first().trimEnd('&').toInt()
        val rel = t - e.start
        val n = base + Math.floor(rel).toInt()
        val frac = rel - Math.floor(rel)
        return if (frac >= swing - 0.02 && counts.contains("$n&")) "$n&" else "$n"
    }

    /** The distinct beat instants (counts and swung "&"s) of the whole timeline, in order: the stops of the exploration view. */
    fun beatInstants(): List<Double> = composed.steps.map { it.t }.distinct().sorted()

    /** All feet at an instant: key "L.L" etc. */
    fun feetAt(t: Double): Map<String, Placement> {
        val m = LinkedHashMap<String, Placement>()
        for (d in listOf('L', 'F')) for (f in listOf('L', 'R')) m["$d.$f"] = placement(d, f, t)
        return m
    }

    /** Body centre and heading from two feet. */
    fun body(a: Placement, b: Placement): Placement {
        val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
        val sx = Math.sin(Math.toRadians(a.h)) + Math.sin(Math.toRadians(b.h))
        val sy = Math.cos(Math.toRadians(a.h)) + Math.cos(Math.toRadians(b.h))
        return Placement(cx, cy, Math.toDegrees(Math.atan2(sx, sy)))
    }
}

/** A smoothing helper for a following camera. */
class Smoother(private val tau: Double) {
    private var x = Double.NaN; private var y = Double.NaN; private var last = 0L
    fun update(nx: Double, ny: Double, nowMs: Long): Pair<Double, Double> {
        if (x.isNaN()) { x = nx; y = ny; last = nowMs; return Pair(x, y) }
        val dt = (nowMs - last) / 1000.0; last = nowMs
        val k = 1 - exp(-dt / tau)
        x += (nx - x) * k; y += (ny - y) * k
        return Pair(x, y)
    }
    fun reset() { x = Double.NaN; y = Double.NaN }
}
