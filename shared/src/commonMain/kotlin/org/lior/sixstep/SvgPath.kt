package org.lior.sixstep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A path given as SVG path data (the `d` of a `<path>`, as in design/lucide/), made into moves, lines and cubic curves
 *  in absolute coordinates, arcs turned into cubics, so that the shared Draw can trace it (trace). It reads M, L, H, V,
 *  C, S, A and Z, in capitals (absolute) and small letters (relative): what Lucide's icons are written with. */
class SvgPath(data: String) {
    /** 'M' (x, y), 'L' (x, y), 'C' (x1, y1, x2, y2, x, y) or 'Z' (), one after the other */
    val ops = ArrayList<Pair<Char, DoubleArray>>()

    init { parse(data) }

    /** trace it onto `d`, every point taken through `x` and `y` (where the path's units go on the canvas); the caller
     *  begins the path before and fills or strokes it after, so that several can make one path */
    fun trace(d: Draw, x: (Double) -> Double, y: (Double) -> Double) {
        for ((op, a) in ops) when (op) {
            'M' -> d.moveTo(x(a[0]), y(a[1]))
            'L' -> d.lineTo(x(a[0]), y(a[1]))
            'C' -> d.cubicTo(x(a[0]), y(a[1]), x(a[2]), y(a[3]), x(a[4]), y(a[5]))
            'Z' -> d.closePath()
        }
    }

    // ------------------------------------------------------------------ reading

    private var at = 0
    private lateinit var s: String

    private fun parse(data: String) {
        s = data; at = 0
        var cx = 0.0; var cy = 0.0          // the current point
        var sx = 0.0; var sy = 0.0          // where the subpath started
        var lastC2x = 0.0; var lastC2y = 0.0; var lastWasC = false   // for S: the last cubic's second control point
        var cmd = ' '
        while (true) {
            skip()
            if (at >= s.length) break
            val ch = s[at]
            if (ch.isLetter()) { cmd = ch; at++ } else if (cmd == ' ') throw IllegalArgumentException("SVG path: a number before any command in \"$s\"")
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0.0; val oy = if (rel) cy else 0.0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + num(); cy = oy + num(); sx = cx; sy = cy
                    ops.add(Pair('M', doubleArrayOf(cx, cy))); lastWasC = false
                    cmd = if (rel) 'l' else 'L'   // more pairs after a move are lines
                }
                'L' -> { cx = ox + num(); cy = oy + num(); ops.add(Pair('L', doubleArrayOf(cx, cy))); lastWasC = false }
                'H' -> { cx = ox + num(); ops.add(Pair('L', doubleArrayOf(cx, cy))); lastWasC = false }
                'V' -> { cy = oy + num(); ops.add(Pair('L', doubleArrayOf(cx, cy))); lastWasC = false }
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num()
                    cx = ox + num(); cy = oy + num()
                    ops.add(Pair('C', doubleArrayOf(x1, y1, x2, y2, cx, cy)))
                    lastC2x = x2; lastC2y = y2; lastWasC = true
                }
                'S' -> {
                    val x1 = if (lastWasC) 2 * cx - lastC2x else cx; val y1 = if (lastWasC) 2 * cy - lastC2y else cy
                    val x2 = ox + num(); val y2 = oy + num()
                    cx = ox + num(); cy = oy + num()
                    ops.add(Pair('C', doubleArrayOf(x1, y1, x2, y2, cx, cy)))
                    lastC2x = x2; lastC2y = y2; lastWasC = true
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = flag(); val sweep = flag()
                    val x = ox + num(); val y = oy + num()
                    arc(cx, cy, rx, ry, rot, large, sweep, x, y)
                    cx = x; cy = y; lastWasC = false
                }
                'Z' -> { ops.add(Pair('Z', DoubleArray(0))); cx = sx; cy = sy; lastWasC = false; cmd = ' ' }
                else -> throw IllegalArgumentException("SVG path: '$cmd' is not read here (\"$s\")")
            }
        }
    }

    private fun skip() { while (at < s.length && (s[at].isWhitespace() || s[at] == ',')) at++ }

    /** a number: a sign, digits, a point and digits, an exponent ("-.86", "4.5", "1e-3"); one can follow another
     *  with no space between ("4.5-.86", ".5.5") */
    private fun num(): Double {
        skip()
        val from = at
        if (at < s.length && (s[at] == '-' || s[at] == '+')) at++
        while (at < s.length && s[at].isDigit()) at++
        if (at < s.length && s[at] == '.') { at++; while (at < s.length && s[at].isDigit()) at++ }
        if (at < s.length && (s[at] == 'e' || s[at] == 'E')) {
            at++
            if (at < s.length && (s[at] == '-' || s[at] == '+')) at++
            while (at < s.length && s[at].isDigit()) at++
        }
        if (at == from) throw IllegalArgumentException("SVG path: a number wanted at $at in \"$s\"")
        return s.substring(from, at).toDouble()
    }

    /** an arc's flag: one digit, 0 or 1, which may run into the next number ("a2 2 0 014 0") */
    private fun flag(): Boolean {
        skip()
        val c = s.getOrNull(at) ?: throw IllegalArgumentException("SVG path: an arc flag wanted at $at in \"$s\"")
        if (c != '0' && c != '1') throw IllegalArgumentException("SVG path: an arc flag wanted at $at in \"$s\"")
        at++
        return c == '1'
    }

    /** an elliptical arc from (x1, y1) to (x2, y2), as SVG draws it (its implementation notes: from the end points to
     *  the centre), in cubics of at most a quarter turn each */
    private fun arc(x1: Double, y1: Double, rx0: Double, ry0: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0); var ry = abs(ry0)
        if (rx == 0.0 || ry == 0.0) { ops.add(Pair('L', doubleArrayOf(x2, y2))); return }
        val phi = rotDeg * PI / 180; val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2; val dy = (y1 - y2) / 2
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1) { rx *= sqrt(lam); ry *= sqrt(lam) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val k = (if (large == sweep) -1.0 else 1.0) * sqrt(max(0.0, num / den))
        val cxp = k * rx * y1p / ry; val cyp = -k * ry * x1p / rx
        val ccx = cp * cxp - sp * cyp + (x1 + x2) / 2; val ccy = sp * cxp + cp * cyp + (y1 + y2) / 2
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val t1 = angle(1.0, 0.0, ux, uy)
        var dt = angle(ux, uy, vx, vy)
        if (!sweep && dt > 0) dt -= 2 * PI else if (sweep && dt < 0) dt += 2 * PI
        val n = max(1, ceil(abs(dt) / (PI / 2) - 1e-9).toInt())
        val step = dt / n
        val h = 4.0 / 3.0 * tan(step / 4)
        fun px(a: Double) = ccx + cp * rx * cos(a) - sp * ry * sin(a)
        fun py(a: Double) = ccy + sp * rx * cos(a) + cp * ry * sin(a)
        fun dxa(a: Double) = -cp * rx * sin(a) - sp * ry * cos(a)
        fun dya(a: Double) = -sp * rx * sin(a) + cp * ry * cos(a)
        for (i in 0 until n) {
            val a1 = t1 + i * step; val a2 = a1 + step
            val ex = if (i == n - 1) x2 else px(a2); val ey = if (i == n - 1) y2 else py(a2)
            ops.add(Pair('C', doubleArrayOf(px(a1) + h * dxa(a1), py(a1) + h * dya(a1), px(a2) - h * dxa(a2), py(a2) - h * dya(a2), ex, ey)))
        }
    }
}
