package org.lior.sixstep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A step's arrow, drawn at a stop once the feet are there: the foot's way from where it stood, through the anchors of
 *  its path (if it has any), to where it landed. The headings keep their winding, so `to.h - from.h` is the turn the
 *  foot makes, clockwise when positive. */
data class Arrow(val dancer: Char, val from: Placement, val to: Placement, val via: List<Pair<Double, Double>> = emptyList())

/** Everything the floor shows at one instant. */
class Scene(
    val feet: Map<String, Placement>,
    val weights: Map<Char, Map<Char, Double>>,
    val title: String = "",
    val count: String = "",
    val arrows: List<Arrow> = emptyList(),
    val banner: String? = null,
    val titleColor: Int? = null
)

/** The dancers' feet seen from above, through a camera: the world point (cx, cy) in cm is drawn at the screen point
 *  (ox, oy), `scale` px per cm. Shoes are dancing-shoe soles (the dancer's colour with the weight as its opacity, over a
 *  grey silhouette; the pointed toe shows the heading), a stop's dashed step arrows along the feet's paths with their
 *  turn marks, the 50 cm grid.
 *  The one drawing of the dancers: the dance floor, the strips of step pictures and the launcher's little floor all use
 *  it. `density`: screen px per dp (1 on a web page, whose px already are that). */
class FeetPainter(private val density: Double) {
    /** one of the screen's own pixels, in the px this draws in: 1 where those are the same (an Android canvas), and
     *  1 / devicePixelRatio on a web page. The grid's lines are that thin, and the few sizes that are plain screen
     *  pixels in the Android app are measured in it, so that both fronts draw the same picture. */
    var pixel = 1.0
    companion object {
        const val GRID = 50.0
        const val TURN_MARK = 90.0        // degrees: a step that turns its foot by more gets a turn mark (user 2026-11-16)
        // the turn mark: a small round arrow in the dancer's colour, no number. Its sizes are cm of floor; zoomed out it
        // stops shrinking at MARK_MIN_R_DP, so that it can still be read
        const val MARK_R = 2.2            // the radius of its arc
        const val MARK_W = 0.75           // its line
        const val MARK_HEAD_L = 1.9
        const val MARK_HEAD_W = 1.2
        const val MARK_REACH = MARK_R + MARK_HEAD_W   // how far a mark reaches from its centre
        const val MARK_MIN_R_DP = 7.0     // the arc's radius is never less than this on the screen
        const val MARK_CLEAR = 1.0        // cm of free floor around a mark that is good enough
        val MARK_ALONG = doubleArrayOf(0.5, 0.35, 0.65, 0.2, 0.8)     // where along its arrow a mark may sit: the middle first
        val MARK_BESIDE = doubleArrayOf(5.5, 8.0, 11.0)               // and how far beside it (cm from the path to the mark's centre)
        val SHOE_SPOTS = doubleArrayOf(-11.0, -5.5, 0.0, 5.5, 11.0)   // a shoe as five discs of 5 cm along its length (the room around a mark)

        /** the sole of a dancing shoe seen from below, in cm, for a LEFT foot: u across (+ towards the inner, big-toe
         *  side), v along (+ towards the toe), centred on the foot's placement, 26 cm long. The front sole runs from a
         *  pointed toe to the waist; the heel is a separate piece behind a small gap (user 2026-10-19: "the bottom of a
         *  dancing shoe"). Each outline is a start point and then cubic segments of (control 1, control 2, end). A right
         *  foot is the mirror. */
        val SOLE = doubleArrayOf(2.6, -3.0,
            3.0, 0.5, 4.8, 3.0, 4.6, 6.0,
            4.4, 9.5, 2.8, 12.4, 1.2, 13.0,
            -0.6, 13.4, -5.2, 8.5, -5.0, 4.5,
            -4.9, 1.5, -3.6, -1.0, -3.4, -3.0,
            -1.5, -2.7, 0.8, -2.7, 2.6, -3.0)
        val HEEL = doubleArrayOf(2.7, -4.4,
            2.9, -6.5, 2.9, -9.5, 2.4, -11.0,
            1.9, -12.5, 0.9, -13.0, -0.4, -13.0,
            -1.8, -13.0, -2.8, -12.4, -3.3, -11.0,
            -3.8, -9.5, -3.6, -6.5, -3.4, -4.4,
            -1.3, -4.1, 0.6, -4.1, 2.7, -4.4)
    }

    /** the floor that can be seen once the camera has settled: its edges in cm (y up) and its px per cm. The turn marks
     *  are placed for it, so that they keep their places while the camera glides. */
    class Sight(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val scale: Double)

    var ox = 0.0
    var oy = 0.0
    var cx = 0.0
    var cy = 0.0
    var scale = 4.0

    private fun sx(x: Double): Double = ox + (x - cx) * scale
    private fun sy(y: Double): Double = oy - (y - cy) * scale

    /** the grid lines inside a rectangle of the screen */
    fun grid(d: Draw, l: Double, t: Double, r: Double, b: Double) {
        var x = floor((cx + (l - ox) / scale) / GRID) * GRID
        val x1 = cx + (r - ox) / scale
        while (x <= x1) { val px = sx(x); d.line(px, t, px, b, Colors.FLOOR_GRID, pixel); x += GRID }
        var y = floor((cy - (b - oy) / scale) / GRID) * GRID
        val y1 = cy - (t - oy) / scale
        while (y <= y1) { val py = sy(y); d.line(l, py, r, py, Colors.FLOOR_GRID, pixel); y += GRID }
    }

    /** the shoes, the lightest first so that the ones carrying weight are on top */
    fun feet(d: Draw, feet: Map<String, Placement>, weights: Map<Char, Map<Char, Double>>) {
        for (k in feet.keys.sortedBy { k -> weights[k[0]]?.get(k[2]) ?: 0.0 }) {
            val p = feet[k] ?: continue
            shoePath(d, p, k[2])
            d.fillPath(withAlpha(Colors.SHOE_GREY, 0.2))
            val w = weights[k[0]]?.get(k[2]) ?: 0.0
            if (w > 0.001) d.fillPath(withAlpha(if (k[0] == 'L') Colors.LEADER else Colors.FOLLOWER, w))
        }
    }

    /** a stop's step arrows, over the shoes: each follows the path its foot really takes (through the anchors set in
     *  the editor), and beside the arrow of a step that turns its foot by more than TURN_MARK degrees a small round
     *  arrow shows which way the foot turns (user 2026-11-16). `sight`: the floor that can be seen (not under a bar),
     *  where the turn marks have to be; null when all of it can. */
    fun arrows(d: Draw, arrows: List<Arrow>, feet: Map<String, Placement>, sight: Sight? = null) {
        if (arrows.isEmpty()) return
        val paths = arrows.map { a -> Curve.samples(listOf(Pair(a.from.x, a.from.y)) + a.via + listOf(Pair(a.to.x, a.to.y))) }
        for ((k, a) in arrows.withIndex()) dashedArrow(d, colorOf(a.dancer), paths[k])
        val marks = placeMarks(arrows, paths, feet, sight)
        for ((k, a) in arrows.withIndex()) {
            val m = marks[k] ?: continue
            turnMark(d, colorOf(a.dancer), m.first, m.second, a.to.h - a.from.h > 0)
        }
    }

    private fun colorOf(dancer: Char): Int = if (dancer == 'L') Colors.LEADER else Colors.FOLLOWER
    private fun darker(color: Int): Int = Colors.rgb((Colors.red(color) * 0.72).toInt(), (Colors.green(color) * 0.72).toInt(), (Colors.blue(color) * 0.72).toInt())
    /** how much bigger than its floor size a turn mark is drawn at `pxPerCm`: 1, or more when the floor is zoomed out */
    private fun markGrow(pxPerCm: Double): Double = max(1.0, MARK_MIN_R_DP * density / (MARK_R * pxPerCm))

    private fun pathLength(p: List<Pair<Double, Double>>): Double {
        var len = 0.0
        for (i in 1 until p.size) len += hypot(p[i].first - p[i - 1].first, p[i].second - p[i - 1].second)
        return len
    }

    /** the point at fraction f of a path's length and the direction of the path there: x, y, dx, dy */
    private fun along(p: List<Pair<Double, Double>>, f: Double): DoubleArray {
        val last = p.last()
        val total = pathLength(p)
        if (total < 1e-9) return doubleArrayOf(last.first, last.second, 0.0, 1.0)
        val want = f * total
        var run = 0.0
        for (i in 0 until p.size - 1) {
            val dx = p[i + 1].first - p[i].first; val dy = p[i + 1].second - p[i].second
            val len = hypot(dx, dy)
            if (len > 1e-12 && run + len >= want) {
                val u = (want - run) / len
                return doubleArrayOf(p[i].first + dx * u, p[i].second + dy * u, dx / len, dy / len)
            }
            run += len
        }
        return doubleArrayOf(last.first, last.second, 0.0, 1.0)
    }

    /** where each turn mark goes (cm of floor), null for an arrow without one: beside the middle of its arrow, on the side
     *  and at the distance that leave it free floor, clear of the shoes, of every arrow and of the marks already placed,
     *  and where it can be seen. The places are tried the middle of the arrow and the nearest distance first; the first
     *  with MARK_CLEAR cm of room around it is taken, and when there is none, the one with the most room. */
    private fun placeMarks(arrows: List<Arrow>, paths: List<List<Pair<Double, Double>>>, feet: Map<String, Placement>, sight: Sight?): List<Pair<Double, Double>?> {
        fun turns(a: Arrow): Boolean = abs(a.to.h - a.from.h) > TURN_MARK + 0.01   // more than a quarter turn, not a quarter turn itself
        if (arrows.none { turns(it) }) return arrows.map { null }
        val grow = markGrow(sight?.scale ?: scale)
        val reach = MARK_REACH * grow
        val obstacles = ArrayList<DoubleArray>()   // x, y, radius (cm)
        for (p in feet.values) {
            val f = fwd(p.h)
            for (o in SHOE_SPOTS) obstacles.add(doubleArrayOf(p.x + f.first * o, p.y + f.second * o, 5.0))
        }
        for (pts in paths) {
            val n = max(1, (pathLength(pts) / 2.0).toInt())
            for (i in 0..n) { val q = along(pts, i.toDouble() / n); obstacles.add(doubleArrayOf(q[0], q[1], 1.0)) }
            obstacles.add(doubleArrayOf(pts.last().first, pts.last().second, 2.5))   // the arrow's head
        }
        // the free floor around a mark centred at (x, y), in cm; far below zero where it could not be seen
        fun room(x: Double, y: Double): Double {
            if (sight != null) {
                val outside = max(max(sight.x0 - (x - reach), (x + reach) - sight.x1), max(sight.y0 - (y - reach), (y + reach) - sight.y1))
                if (outside > 0.0) return -1000.0 - outside
            }
            var least = Double.MAX_VALUE
            for (o in obstacles) least = min(least, hypot(x - o[0], y - o[1]) - o[2])
            return least - reach
        }
        val out = ArrayList<Pair<Double, Double>?>()
        for ((k, a) in arrows.withIndex()) {
            if (!turns(a)) { out.add(null); continue }
            val pts = paths[k]
            // the places to try, in order; the two sides of the arrow are tried together
            val groups = ArrayList<List<Pair<Double, Double>>>()
            if (pathLength(pts) < 1.0) {
                // a turn on the spot: around the foot
                for (dist in doubleArrayOf(12.0, 16.0)) for (deg in 0 until 360 step 45) {
                    val g = deg * PI / 180.0
                    groups.add(listOf(Pair(a.to.x + sin(g) * dist * grow, a.to.y + cos(g) * dist * grow)))
                }
            } else {
                for (f in MARK_ALONG) {
                    val m = along(pts, f)
                    for (d0 in MARK_BESIDE) {
                        val dist = d0 * grow
                        groups.add(listOf(Pair(m[0] - m[3] * dist, m[1] + m[2] * dist), Pair(m[0] + m[3] * dist, m[1] - m[2] * dist)))
                    }
                }
            }
            var best: Pair<Double, Double>? = null
            var bestRoom = -Double.MAX_VALUE
            var chosen: Pair<Double, Double>? = null
            for (group in groups) {
                var side: Pair<Double, Double>? = null
                var sideRoom = -Double.MAX_VALUE
                for (q in group) { val r = room(q.first, q.second); if (r > sideRoom) { sideRoom = r; side = q } }
                if (sideRoom > bestRoom) { bestRoom = sideRoom; best = side }
                if (sideRoom >= MARK_CLEAR) { chosen = side; break }
            }
            val place = chosen ?: best
            out.add(place)
            if (place != null) obstacles.add(doubleArrayOf(place.first, place.second, reach))
        }
        return out
    }

    /** both pieces of the shoe at placement p as the canvas's path (the transform is affine, so the curves map exactly) */
    private fun shoePath(d: Draw, p: Placement, side: Char) {
        val f = fwd(p.h); val r = right(p.h); val m = if (side == 'L') 1.0 else -1.0
        fun ax(u: Double, v: Double): Double = sx(p.x + u * m * r.first + v * f.first)
        fun ay(u: Double, v: Double): Double = sy(p.y + u * m * r.second + v * f.second)
        d.beginPath()
        for (o in listOf(SOLE, HEEL)) {
            d.moveTo(ax(o[0], o[1]), ay(o[0], o[1]))
            var i = 2
            while (i + 5 < o.size) {
                d.cubicTo(ax(o[i], o[i + 1]), ay(o[i], o[i + 1]), ax(o[i + 2], o[i + 3]), ay(o[i + 2], o[i + 3]), ax(o[i + 4], o[i + 5]), ay(o[i + 4], o[i + 5]))
                i += 6
            }
            d.closePath()
        }
    }

    /** a step's dashed arrow along the foot's path, drawn over the shoes: the dancer's colour darkened, every dash and the
     *  head outlined in black, so that it stands apart from the feet (user 2026-10-28). The head points the way the path
     *  ends. */
    private fun dashedArrow(d: Draw, color: Int, pts: List<Pair<Double, Double>>) {
        val px = DoubleArray(pts.size) { sx(pts[it].first) }
        val py = DoubleArray(pts.size) { sy(pts[it].second) }
        var len = 0.0
        for (i in 1 until px.size) len += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        if (len < 4 * pixel) return
        val dark = darker(color)
        val w = max(2.0 * pixel, scale * 1.2)
        val edge = max(1.0 * pixel, scale * 0.35)
        d.beginPath(); d.moveTo(px[0], py[0])
        for (i in 1 until px.size) d.lineTo(px[i], py[i])
        // the same dashes twice: black and wider, then the colour; the round caps give each dash its border at the ends too
        val dash = doubleArrayOf(scale * 3, scale * 2.2)
        d.strokePath(Colors.BLACK, w + 2 * edge, dash)
        d.strokePath(dark, w, dash)
        // the head: along the path over the head's own length, so that it sits on a curve as it does on a line
        val n = px.size - 1
        val bx = px[n]; val by = py[n]
        val s = scale * 4.5
        var backX = px[0]; var backY = py[0]
        for (i in n - 1 downTo 0) {
            backX = px[i]; backY = py[i]
            if (hypot(bx - backX, by - backY) >= s) break
        }
        val dl = hypot(bx - backX, by - backY)
        if (dl < 1e-3) return
        val ux = (bx - backX) / dl; val uy = (by - backY) / dl
        d.beginPath(); d.moveTo(bx, by)
        d.lineTo(bx - ux * s - uy * s * 0.55, by - uy * s + ux * s * 0.55)
        d.lineTo(bx - ux * s + uy * s * 0.55, by - uy * s - ux * s * 0.55)
        d.closePath()
        d.fillPath(dark)
        d.strokePath(Colors.BLACK, edge * 1.5)
    }

    /** a turn mark centred at (x, y) cm: a small round arrow, three quarters of a circle from the top, clockwise for a foot
     *  that turns to the right, in the dancer's own colour as the editor's turn arrows are (blue for the leader, pink for
     *  the follower; user 2026-11-16): no darkening and no black edge, which hid the colour at this size; no number */
    private fun turnMark(d: Draw, color: Int, x: Double, y: Double, clockwise: Boolean) {
        val k = scale * markGrow(scale)   // px per cm of the mark itself
        val px = sx(x); val py = sy(y)
        val r = MARK_R * k
        val sweep = if (clockwise) 270.0 else -270.0
        d.strokeArc(px, py, r, -90.0, sweep, color, MARK_W * k)
        // the head at the end of the arc, pointing on round the circle (the screen's angles run clockwise, y down)
        val end = (-90.0 + sweep) * PI / 180.0
        val way = if (clockwise) 1.0 else -1.0
        val ex = px + cos(end) * r; val ey = py + sin(end) * r
        val tx = -sin(end) * way; val ty = cos(end) * way
        val hl = MARK_HEAD_L * k; val hw = MARK_HEAD_W * k
        d.beginPath()
        d.moveTo(ex + tx * hl, ey + ty * hl)
        d.lineTo(ex - ty * hw, ey + tx * hw)
        d.lineTo(ex + ty * hw, ey - tx * hw)
        d.closePath()
        d.fillPath(color)
    }
}

/** The dance floor from above: the feet (FeetPainter), the title at the top left, the count at the top right, and the
 *  annotation banner at the top (the dark box, user 2026-10-26).
 *
 *  The camera (user 2026-10-19): the feet of a couple standing side by side fill about half the screen width; when the
 *  feet's bounding box gets close to an edge the view glides to centre it; when the box is too big for the screen the
 *  view zooms out just enough, and zooms back in once the box is small again. The part of the floor under the dance
 *  floor's bars (insetTop, insetBottom) does not count: the dancers stay between the bars.
 *
 *  It knows nothing of the screen it is on: the front tells it the size, gives it the scene and the time, and has it draw
 *  on a canvas. `draw` and the setters answer whether the camera is still gliding, that is, whether the front should
 *  draw again at the next frame. */
class FloorPicture(density: Double) {
    companion object {
        const val STANCE_BOX = 92.0      // cm: the width of two pairs of feet side by side, toes and arrows included
        const val EDGE = 0.10            // the box must stay out of this fraction of the visible floor at each edge
        const val BOX_MARGIN = 16.0      // cm around the foot centres for the silhouettes and arrows
        const val CAMERA_TAU = 0.225     // s: how fast the camera glides after the couple (user 2026-10-19: twice as fast as 0.45)
    }
    private val painter = FeetPainter(density)
    /** one of the screen's own pixels, in the px this draws in (FeetPainter.pixel) */
    var pixel: Double
        get() = painter.pixel
        set(v) { painter.pixel = v }

    var scene: Scene? = null
        private set
    /** world centre (cm) and px per cm */
    var cx = 10.0
    var cy = 20.0
    var scale = 4.0
    var autoCamera = true
    var showLabels = true
    var width = 0.0
        private set
    var height = 0.0
        private set
    /** px of the floor hidden under the bars at the top and the bottom */
    var insetTop = 0.0
        private set
    var insetBottom = 0.0
        private set
    private var baseScale = 0.0
    private var targetScale = 0.0
    private var targetCx = Double.NaN
    private var targetCy = Double.NaN
    private var tracking = false
    private var lastFrame = Double.NaN

    private fun visibleH(): Double = max(1.0, height - insetTop - insetBottom)
    private fun midY(): Double = insetTop + visibleH() / 2

    fun setSize(w: Double, h: Double) {
        width = w; height = h
        baseScale = w / 2.0 / STANCE_BOX
        if (autoCamera) { scale = baseScale; targetScale = baseScale; scene?.let { snapTo(it) } }
    }

    /** true: the camera is gliding, draw again at the next frame */
    fun setScene(s: Scene?, nowMs: Double): Boolean {
        scene = s
        return if (autoCamera) updateCamera(nowMs) else false
    }

    fun setInsets(top: Double, bottom: Double, nowMs: Double): Boolean {
        insetTop = top; insetBottom = bottom
        return if (autoCamera) updateCamera(nowMs) else false
    }

    /** put the camera straight on the scene's feet (no glide) */
    private fun snapTo(s: Scene) {
        val b = box(s) ?: return
        cx = (b[0] + b[2]) / 2; cy = (b[1] + b[3]) / 2; targetCx = cx; targetCy = cy
        scale = fitScale(b); targetScale = scale; tracking = false
    }

    /** the feet's bounding box in cm: x0, y0, x1, y1 */
    private fun box(s: Scene): DoubleArray? {
        if (s.feet.isEmpty()) return null
        val xs = s.feet.values.map { it.x }; val ys = s.feet.values.map { it.y }
        return doubleArrayOf(xs.min() - BOX_MARGIN, ys.min() - BOX_MARGIN, xs.max() + BOX_MARGIN, ys.max() + BOX_MARGIN)
    }

    /** the base zoom, or less when the box does not fit inside the visible floor with its edge margins */
    private fun fitScale(b: DoubleArray): Double {
        if (baseScale <= 0 || width <= 0) return scale
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val fit = min(width * (1 - 2 * EDGE) / max(1.0, w), visibleH() * (1 - 2 * EDGE) / max(1.0, h))
        return min(baseScale, fit)
    }

    private fun gliding(): Boolean = abs(targetCx - cx) * scale >= 1.5 || abs(targetCy - cy) * scale >= 1.5 || abs(targetScale - scale) >= 0.01

    private fun updateCamera(nowMs: Double): Boolean {
        val s = scene ?: return false
        val b = box(s) ?: return false
        if (width <= 0) return false
        if (targetCx.isNaN()) { snapTo(s); return false }
        targetScale = fitScale(b)
        // does the box come close to an edge of the visible floor at the current zoom? then glide to centre it
        val my = midY(); val vh = visibleH()
        val left = width / 2 + (b[0] - cx) * scale; val rightEdge = width / 2 + (b[2] - cx) * scale
        val top = my - (b[3] - cy) * scale; val bottom = my - (b[1] - cy) * scale
        val near = left < width * EDGE || rightEdge > width * (1 - EDGE) || top < insetTop + vh * EDGE || bottom > insetTop + vh * (1 - EDGE)
        if (near || targetScale < scale * 0.999) tracking = true
        if (tracking) { targetCx = (b[0] + b[2]) / 2; targetCy = (b[1] + b[3]) / 2 }
        glide(nowMs, 0.016)
        if (!gliding()) { cx = targetCx; cy = targetCy; scale = targetScale; tracking = false; return false }
        return true
    }

    private fun glide(nowMs: Double, first: Double) {
        val dt = if (lastFrame.isNaN()) first else ((nowMs - lastFrame) / 1000.0).coerceIn(0.0, 0.1)
        lastFrame = nowMs
        val k = 1 - exp(-dt / CAMERA_TAU)
        cx += (targetCx - cx) * k; cy += (targetCy - cy) * k; scale += (targetScale - scale) * k
    }

    /** draw the floor; true: the camera is still gliding (a floor standing still still animates its camera), draw again */
    fun draw(d: Draw, nowMs: Double): Boolean {
        val s = scene
        var again = false
        if (autoCamera && s != null && !lastFrame.isNaN() && box(s) != null && gliding()) { glide(nowMs, 0.0); again = true }
        painter.ox = width / 2; painter.oy = midY(); painter.cx = cx; painter.cy = cy; painter.scale = scale
        d.fillAll(Colors.FLOOR)
        painter.grid(d, 0.0, 0.0, width, height)
        if (s == null) return again
        painter.feet(d, s.feet, s.weights)
        painter.arrows(d, s.arrows, s.feet, sight())   // over the shoes (user 2026-10-28)
        if (showLabels) labels(d, s)
        return again
    }

    /** the floor between the bars as the camera shows it once it has settled */
    private fun sight(): FeetPainter.Sight {
        val ahead = autoCamera && !targetCx.isNaN() && targetScale > 0
        val sc = if (ahead) targetScale else scale
        val x = if (ahead) targetCx else cx
        val y = if (ahead) targetCy else cy
        val my = midY()
        return FeetPainter.Sight(x - width / 2.0 / sc, y - (height - insetBottom - my) / sc, x + width / 2.0 / sc, y + (my - insetTop) / sc, sc)
    }

    private fun labels(d: Draw, s: Scene) {
        val w = width; val h = height
        val titleSize = max(h * 0.045, 18.0 * pixel)
        d.text(s.title, h * 0.03, h * 0.03 + titleSize, titleSize, s.titleColor ?: Colors.INK, FontWeight.BOLD, TextAlign.LEFT)
        val countSize = max(h * 0.11, 32.0 * pixel)
        d.text(s.count, w - h * 0.03, h * 0.03 + countSize * 0.85, countSize, Colors.INK, FontWeight.BOLD, TextAlign.RIGHT)
        val banner = s.banner
        if (!banner.isNullOrBlank()) {
            // the annotation as it always looked, at the top under the title and the count, out of the dancers' way (user 2026-10-26)
            val size = max(h * 0.12, 28.0 * pixel)
            val lines = banner.split('\n').flatMap { wrapText(d, it, size, FontWeight.BOLD, w * 0.9) }
            val bh = lines.size * size * 1.15
            val my = h * 0.15 + size * 0.5 + bh / 2   // the box's middle: its top just under the count
            d.fillRoundRect(w * 0.04, my - bh / 2 - size * 0.5, w * 0.96, my + bh / 2 + size * 0.3, 24.0 * pixel, withAlpha(Colors.BANNER, 0.78))
            lines.forEachIndexed { i, ln -> d.text(ln, w / 2, my - bh / 2 + size * (i + 0.9) * 1.15, size, Colors.ACCENT, FontWeight.BOLD, TextAlign.CENTER) }
        }
    }
}
