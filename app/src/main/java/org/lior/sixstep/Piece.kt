package org.lior.sixstep

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Something to dance: one version of an element (by its index in DanceData.variants), a building block (a
 *  sub-composition) or a composition. The dance floor is opened with its kind, name and variant. */
class Piece(val kind: String, val name: String, val variant: Int, val seq: List<SeqItem>, val startState: String, val free: Char?) {
    val isComposition: Boolean get() = kind == COMPOSITION

    fun compose(data: DanceData, swing: Double, padToLoop: Boolean = false): Composed =
        Composer.compose(data, seq, startState, swing, free, padToLoop)

    companion object {
        const val ELEMENT = "element"
        const val BLOCK = "block"
        const val COMPOSITION = "composition"
        const val LEAD_IN = 1.0     // beats of the start position shown before count 1: the first step is seen leaving it
        const val SWING = 0.67      // the "&" when no track says otherwise

        fun find(data: DanceData, store: CompositionStore, kind: String, name: String, variant: Int = 0): Piece? = when (kind) {
            ELEMENT -> data.variants(name).getOrNull(variant)?.let { v ->
                // the ending is pinned, so that the composer takes exactly this version
                Piece(kind, name, variant, listOf(SeqItem(name, null, null, 0, true, name, v.endState)), v.startState, v.leaderFreeStart)
            }
            BLOCK -> store.subs.firstOrNull { it.name == name }?.let { Piece(kind, name, 0, store.sequence(it), it.startState, null) }
            else -> store.find(name)?.let { Piece(COMPOSITION, name, 0, store.sequence(it), it.startState, null) }
        }
    }
}

/** The stops of a timeline: the start position one beat before count 1, count 1, then every instant a foot lands. */
fun stopsOf(tl: Timeline): List<Double> = (listOf(-Piece.LEAD_IN, 0.0) + tl.beatInstants().filter { it > 0 }).distinct()

/** the last stop at or before beat b (the first one before them all) */
fun stopIndex(stops: List<Double>, b: Double): Int {
    var lo = 0; var hi = stops.size - 1; var ans = 0
    while (lo <= hi) { val mid = (lo + hi) / 2; if (stops[mid] <= b + 1e-6) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
    return ans
}

/** a picture per stop: the feet where they landed, the weights once they have settled on them, the count */
fun cellsOf(tl: Timeline, stops: List<Double>, startLabel: String): List<StepStrip.Cell> = stops.map { s ->
    val w = tl.settledAfter(s)
    StepStrip.Cell(tl.feetAt(s), mapOf('L' to tl.weights('L', w), 'F' to tl.weights('F', w)), if (s < 0) startLabel else tl.countLabel(s))
}

/** How unusual a pose is, for the explore page's rows, which open centred on the step of their piece that stands out
 *  most (the user's draft, 2026-10-28). A pose is described relative to the couple: the four feet around their middle,
 *  turned so that the direction from his feet to hers is +x, each foot's heading as a short vector. Its density is the
 *  sum of a Gaussian kernel over the poses of every element; the lowest density stands out most. */
object Rarity {
    private const val SIGMA = 18.0     // cm: poses this far apart count as different
    private const val TURN = 12.0      // cm per radian: how much a foot's heading weighs against its place
    private val KEYS = listOf("L.L", "L.R", "F.L", "F.R")

    fun descriptor(feet: Map<String, Placement>): DoubleArray? {
        val ps = KEYS.map { feet[it] ?: return null }
        val mx = ps.sumOf { it.x } / 4; val my = ps.sumOf { it.y } / 4
        val lx = (ps[0].x + ps[1].x) / 2; val ly = (ps[0].y + ps[1].y) / 2
        val fx = (ps[2].x + ps[3].x) / 2; val fy = (ps[2].y + ps[3].y) / 2
        val a = if (hypot(fx - lx, fy - ly) < 1.0) 0.0 else atan2(fy - ly, fx - lx)
        val ca = cos(a); val sa = sin(a)
        val out = DoubleArray(16)
        for ((i, p) in ps.withIndex()) {
            val dx = p.x - mx; val dy = p.y - my
            out[4 * i] = dx * ca + dy * sa; out[4 * i + 1] = -dx * sa + dy * ca
            val h = Math.toRadians(90.0 - p.h) - a   // the heading as an angle of the maths kind, relative to his-to-her
            out[4 * i + 2] = TURN * cos(h); out[4 * i + 3] = TURN * sin(h)
        }
        return out
    }

    private fun density(d: DoubleArray, corpus: List<DoubleArray>): Double {
        var s = 0.0
        for (q in corpus) {
            var e = 0.0
            for (i in d.indices) { val x = d[i] - q[i]; e += x * x }
            s += exp(-e / (2 * SIGMA * SIGMA))
        }
        return s
    }

    /** the picture to centre a row on: the stop that stands out most (the start position aside), kept one away from the
     *  ends so that there is a picture on each side of it */
    fun centre(cells: List<StepStrip.Cell>, corpus: List<DoubleArray>): Int {
        if (cells.size < 3) return min(1, cells.size - 1)
        var best = 1; var bestD = Double.MAX_VALUE
        for (k in 1 until cells.size) {
            val d = descriptor(cells[k].feet) ?: continue
            val dn = density(d, corpus)
            if (dn < bestD - 1e-9) { bestD = dn; best = k }
        }
        return best.coerceIn(1, cells.size - 2)
    }
}
