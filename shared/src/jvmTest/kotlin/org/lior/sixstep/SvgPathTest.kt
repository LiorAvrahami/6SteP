package org.lior.sixstep

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SvgPath: SVG path data made into moves, lines and cubics, as the hints' hand is drawn from it */
class SvgPathTest {
    private fun end(p: SvgPath): DoubleArray { val (op, a) = p.ops.last { it.first != 'Z' }; return if (op == 'C') doubleArrayOf(a[4], a[5]) else doubleArrayOf(a[0], a[1]) }
    /** a cubic from (x0, y0) at t */
    private fun at(x0: Double, y0: Double, c: DoubleArray, t: Double): DoubleArray {
        val m = 1 - t
        return doubleArrayOf(m * m * m * x0 + 3 * m * m * t * c[0] + 3 * m * t * t * c[2] + t * t * t * c[4],
            m * m * m * y0 + 3 * m * m * t * c[1] + 3 * m * t * t * c[3] + t * t * t * c[5])
    }

    @Test fun linesAbsoluteAndRelative() {
        val p = SvgPath("M10 9.5V4h2l1 1L20 20v-3")
        assertEquals(listOf('M', 'L', 'L', 'L', 'L', 'L'), p.ops.map { it.first })
        assertEquals(listOf(10.0, 9.5, 10.0, 4.0, 12.0, 4.0, 13.0, 5.0, 20.0, 20.0, 20.0, 17.0), p.ops.flatMap { it.second.toList() })
    }

    @Test fun numbersThatRunTogether() {
        val p = SvgPath("M12 22c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6")
        val c = p.ops[1].second
        assertEquals(listOf(9.2, 22.0, 7.5, 21.14, 6.01, 19.66), c.toList().map { kotlin.math.round(it * 1000) / 1000 })
        assertEquals(2.41, end(p)[0], 1e-9); assertEquals(16.06, end(p)[1], 1e-9)
    }

    @Test fun arcsBecomeCubicsOnTheCircle() {
        // a quarter of a circle of 8 round (14, 14), from (22, 14) to (14, 22)
        val q = SvgPath("M22 14a8 8 0 0 1-8 8")
        assertEquals(listOf('M', 'C'), q.ops.map { it.first })
        for (t in listOf(0.25, 0.5, 0.75)) { val pt = at(22.0, 14.0, q.ops[1].second, t); assertEquals(8.0, hypot(pt[0] - 14, pt[1] - 14), 0.01) }
        assertEquals(14.0, end(q)[0], 1e-9); assertEquals(22.0, end(q)[1], 1e-9)
        // half a circle of 2 round (20, 11), over its top: two quarters, meeting at (20, 9)
        val h = SvgPath("M18 11a2 2 0 1 1 4 0")
        assertEquals(listOf('M', 'C', 'C'), h.ops.map { it.first })
        assertEquals(20.0, h.ops[1].second[4], 1e-9); assertEquals(9.0, h.ops[1].second[5], 1e-9)
        // the other way round (sweep 0): under it
        val u = SvgPath("M18 11a2 2 0 0 0 4 0")
        assertEquals(13.0, u.ops[1].second[5], 1e-9)
        // flags written without spaces
        assertEquals(SvgPath("M18 11a2 2 0 1 1 4 0").ops.flatMap { it.second.toList() }, SvgPath("M18 11a2 2 0 114 0").ops.flatMap { it.second.toList() })
    }

    @Test fun theHandsOutlineCloses() {
        val o = SvgPath(HintPainter.HAND_OUTLINE)
        assertEquals('M', o.ops.first().first); assertEquals('Z', o.ops.last().first)
        assertEquals(7.0, end(o)[0], 1e-9); assertEquals(15.0, end(o)[1], 1e-9)
        // every point inside the icon's box
        for ((_, a) in o.ops) for (v in a) assertEquals(true, v >= 0.0 && v <= 24.0, "$v")
    }

    @Test fun whatItCannotRead() {
        assertFailsWith<IllegalArgumentException> { SvgPath("M0 0Q1 1 2 2") }
        assertFailsWith<IllegalArgumentException> { SvgPath("1 2") }
    }
}
