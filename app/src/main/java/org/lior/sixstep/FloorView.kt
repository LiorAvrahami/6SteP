package org.lior.sixstep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A step's arrow in the schema view: the foot's way from where it stood, through the anchors of its path (if it has
 *  any), to where it landed. The headings keep their winding, so `to.h - from.h` is the turn the foot makes, clockwise
 *  when positive. */
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

fun withAlpha(color: Int, alpha: Double): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24)

/** The dancers' feet seen from above, through a camera: the world point (cx, cy) in cm is drawn at the screen point
 *  (ox, oy), `scale` px per cm. Shoes are dancing-shoe soles (the dancer's colour with the weight as its opacity, over a
 *  grey silhouette; the pointed toe shows the heading), the schema view's dashed step arrows along the feet's paths with
 *  their turn marks, the 50 cm grid.
 *  The one drawing of the dancers: the dance floor, the strips of step pictures and the launcher's little floor all use it. */
class FeetPainter(ctx: Context) {
    companion object {
        const val GRID = 50.0
        const val TURN_MARK = 90.0        // degrees: a step that turns its foot by more gets a turn mark (user 2026-11-16)
        // the turn mark: a small round arrow, no number. Its sizes are cm of floor; zoomed out it stops shrinking at
        // MARK_MIN_R_DP, so that it can still be read
        const val MARK_R = 2.2            // the radius of its arc
        const val MARK_W = 0.65           // its line
        const val MARK_EDGE = 0.22        // the black edge of the line and of the head
        const val MARK_HEAD_L = 1.9
        const val MARK_HEAD_W = 1.2
        const val MARK_REACH = MARK_R + MARK_HEAD_W   // how far a mark reaches from its centre
        const val MARK_MIN_R_DP = 7.0     // the arc's radius is never less than this on the screen
        const val MARK_CLEAR = 1.0        // cm of free floor around a mark that is good enough
        val MARK_ALONG = doubleArrayOf(0.5, 0.35, 0.65, 0.2, 0.8)     // where along its arrow a mark may sit: the middle first
        val MARK_BESIDE = doubleArrayOf(5.5, 8.0, 11.0)               // and how far beside it (cm from the path to the mark's centre)
        val SHOE_SPOTS = doubleArrayOf(-11.0, -5.5, 0.0, 5.5, 11.0)   // a shoe as five discs of 5 cm along its length (the room around a mark)
    }
    /** the floor that can be seen once the camera has settled: its edges in cm (y up) and its px per cm. The turn marks
     *  are placed for it, so that they keep their places while the camera glides. */
    class Sight(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val scale: Double)

    private val density = ctx.resources.displayMetrics.density
    private val oval = RectF()
    private val leader = ctx.getColor(R.color.leader)
    private val follower = ctx.getColor(R.color.follower)
    private val grey = Color.rgb(120, 120, 120)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val gridPaint = Paint().apply { color = ctx.getColor(R.color.floor_grid); strokeWidth = 1f }
    private val path = Path()
    var ox = 0f
    var oy = 0f
    var cx = 0.0
    var cy = 0.0
    var scale = 4.0

    fun px(x: Double, y: Double): PointF = PointF((ox + (x - cx) * scale).toFloat(), (oy - (y - cy) * scale).toFloat())

    /** the grid lines inside a rectangle of the screen */
    fun grid(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        var x = floor((cx + (l - ox) / scale) / GRID) * GRID
        val x1 = cx + (r - ox) / scale
        while (x <= x1) { val sx = (ox + (x - cx) * scale).toFloat(); c.drawLine(sx, t, sx, b, gridPaint); x += GRID }
        var y = floor((cy - (b - oy) / scale) / GRID) * GRID
        val y1 = cy - (t - oy) / scale
        while (y <= y1) { val sy = (oy - (y - cy) * scale).toFloat(); c.drawLine(l, sy, r, sy, gridPaint); y += GRID }
    }

    /** the shoes, the lightest first so that the ones carrying weight are on top */
    fun feet(c: Canvas, feet: Map<String, Placement>, weights: Map<Char, Map<Char, Double>>) {
        for (k in feet.keys.sortedBy { k -> weights[k[0]]?.get(k[2]) ?: 0.0 }) {
            val p = feet[k] ?: continue
            val shoe = shoePath(p, k[2])
            fill.color = withAlpha(grey, 0.2); c.drawPath(shoe, fill)
            val w = weights[k[0]]?.get(k[2]) ?: 0.0
            if (w > 0.001) { fill.color = withAlpha(if (k[0] == 'L') leader else follower, w); c.drawPath(shoe, fill) }
        }
    }

    /** the schema view's step arrows, over the shoes: each follows the path its foot really takes (through the anchors
     *  set in the editor), and beside the arrow of a step that turns its foot by more than TURN_MARK degrees a small
     *  round arrow shows which way the foot turns (user 2026-11-16). `sight`: the floor that can be seen (not under a
     *  bar), where the turn marks have to be; null when all of it can. */
    fun arrows(c: Canvas, arrows: List<Arrow>, feet: Map<String, Placement>, sight: Sight? = null) {
        if (arrows.isEmpty()) return
        val paths = arrows.map { a -> Curve.samples(listOf(Pair(a.from.x, a.from.y)) + a.via + listOf(Pair(a.to.x, a.to.y))) }
        for ((k, a) in arrows.withIndex()) dashedArrow(c, colorOf(a.dancer), paths[k])
        val marks = placeMarks(arrows, paths, feet, sight)
        for ((k, a) in arrows.withIndex()) {
            val m = marks[k] ?: continue
            turnMark(c, colorOf(a.dancer), m.first, m.second, a.to.h - a.from.h > 0)
        }
    }

    private fun colorOf(dancer: Char): Int = if (dancer == 'L') leader else follower
    private fun darker(color: Int): Int = Color.rgb((Color.red(color) * 0.72).toInt(), (Color.green(color) * 0.72).toInt(), (Color.blue(color) * 0.72).toInt())
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
                for (d in doubleArrayOf(12.0, 16.0)) for (deg in 0 until 360 step 45) {
                    val g = Math.toRadians(deg.toDouble())
                    groups.add(listOf(Pair(a.to.x + sin(g) * d * grow, a.to.y + cos(g) * d * grow)))
                }
            } else {
                for (f in MARK_ALONG) {
                    val m = along(pts, f)
                    for (d0 in MARK_BESIDE) {
                        val d = d0 * grow
                        groups.add(listOf(Pair(m[0] - m[3] * d, m[1] + m[2] * d), Pair(m[0] + m[3] * d, m[1] - m[2] * d)))
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

    /** the sole of a dancing shoe seen from below, in cm, for a LEFT foot: u across (+ towards the inner, big-toe side),
     *  v along (+ towards the toe), centred on the foot's placement, 26 cm long. The front sole runs from a pointed toe to
     *  the waist; the heel is a separate piece behind a small gap (user 2026-10-19: "the bottom of a dancing shoe").
     *  Each outline is a start point and then cubic segments of (control 1, control 2, end). A right foot is the mirror. */
    private val sole = doubleArrayOf(2.6, -3.0,
        3.0, 0.5, 4.8, 3.0, 4.6, 6.0,
        4.4, 9.5, 2.8, 12.4, 1.2, 13.0,
        -0.6, 13.4, -5.2, 8.5, -5.0, 4.5,
        -4.9, 1.5, -3.6, -1.0, -3.4, -3.0,
        -1.5, -2.7, 0.8, -2.7, 2.6, -3.0)
    private val heel = doubleArrayOf(2.7, -4.4,
        2.9, -6.5, 2.9, -9.5, 2.4, -11.0,
        1.9, -12.5, 0.9, -13.0, -0.4, -13.0,
        -1.8, -13.0, -2.8, -12.4, -3.3, -11.0,
        -3.8, -9.5, -3.6, -6.5, -3.4, -4.4,
        -1.3, -4.1, 0.6, -4.1, 2.7, -4.4)

    /** both pieces of the shoe at placement p on the screen (the transform is affine, so the curves map exactly) */
    private fun shoePath(p: Placement, side: Char): Path {
        val f = fwd(p.h); val r = right(p.h); val m = if (side == 'L') 1.0 else -1.0
        fun at(u: Double, v: Double): PointF = px(p.x + u * m * r.first + v * f.first, p.y + u * m * r.second + v * f.second)
        path.reset()
        for (o in listOf(sole, heel)) {
            val s0 = at(o[0], o[1]); path.moveTo(s0.x, s0.y)
            var i = 2
            while (i + 5 < o.size) {
                val c1 = at(o[i], o[i + 1]); val c2 = at(o[i + 2], o[i + 3]); val e = at(o[i + 4], o[i + 5])
                path.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y); i += 6
            }
            path.close()
        }
        return path
    }

    /** a step's dashed arrow along the foot's path, drawn over the shoes: the dancer's colour darkened, every dash and the
     *  head outlined in black, so that it stands apart from the feet (user 2026-10-28). The head points the way the path
     *  ends. */
    private fun dashedArrow(c: Canvas, color: Int, pts: List<Pair<Double, Double>>) {
        val sp = pts.map { px(it.first, it.second) }
        var len = 0.0
        for (i in 1 until sp.size) len += hypot((sp[i].x - sp[i - 1].x).toDouble(), (sp[i].y - sp[i - 1].y).toDouble())
        if (len < 4) return
        val dark = darker(color)
        val w = max(2.0, scale * 1.2).toFloat()
        val edge = max(1.0, scale * 0.35).toFloat()
        path.reset(); path.moveTo(sp[0].x, sp[0].y)
        for (i in 1 until sp.size) path.lineTo(sp[i].x, sp[i].y)
        // the same dashes twice: black and wider, then the colour; the round caps give each dash its border at the ends too
        stroke.pathEffect = DashPathEffect(floatArrayOf((scale * 3).toFloat(), (scale * 2.2).toFloat()), 0f)
        stroke.color = Color.BLACK; stroke.strokeWidth = w + 2 * edge; c.drawPath(path, stroke)
        stroke.color = dark; stroke.strokeWidth = w; c.drawPath(path, stroke)
        stroke.pathEffect = null
        // the head: along the path over the head's own length, so that it sits on a curve as it does on a line
        val b = sp.last()
        val s = (scale * 4.5).toFloat()
        var back = sp[0]
        for (i in sp.size - 2 downTo 0) {
            back = sp[i]
            if (hypot((b.x - back.x).toDouble(), (b.y - back.y).toDouble()) >= s) break
        }
        val dl = hypot((b.x - back.x).toDouble(), (b.y - back.y).toDouble())
        if (dl < 1e-3) return
        val ux = (b.x - back.x) / dl; val uy = (b.y - back.y) / dl
        path.reset(); path.moveTo(b.x, b.y)
        path.lineTo((b.x - ux * s - uy * s * 0.55).toFloat(), (b.y - uy * s + ux * s * 0.55).toFloat())
        path.lineTo((b.x - ux * s + uy * s * 0.55).toFloat(), (b.y - uy * s - ux * s * 0.55).toFloat())
        path.close()
        fill.color = dark; c.drawPath(path, fill)
        stroke.color = Color.BLACK; stroke.strokeWidth = edge * 1.5f; c.drawPath(path, stroke)
    }

    /** a turn mark centred at (x, y) cm: a small round arrow, three quarters of a circle from the top, clockwise for a foot
     *  that turns to the right; the arrows' darkened colour and black edge; no number (user 2026-11-16) */
    private fun turnMark(c: Canvas, color: Int, x: Double, y: Double, clockwise: Boolean) {
        val k = scale * markGrow(scale)   // px per cm of the mark itself
        val p = px(x, y)
        val r = (MARK_R * k).toFloat()
        val w = (MARK_W * k).toFloat()
        val edge = (MARK_EDGE * k).toFloat()
        val dark = darker(color)
        val sweep = if (clockwise) 270f else -270f
        oval.set(p.x - r, p.y - r, p.x + r, p.y + r)
        stroke.pathEffect = null
        stroke.color = Color.BLACK; stroke.strokeWidth = w + 2 * edge; c.drawArc(oval, -90f, sweep, false, stroke)
        stroke.color = dark; stroke.strokeWidth = w; c.drawArc(oval, -90f, sweep, false, stroke)
        // the head at the end of the arc, pointing on round the circle (the screen's angles run clockwise, y down)
        val end = Math.toRadians(-90.0 + sweep)
        val way = if (clockwise) 1.0 else -1.0
        val ex = p.x + cos(end) * r; val ey = p.y + sin(end) * r
        val tx = -sin(end) * way; val ty = cos(end) * way
        val hl = MARK_HEAD_L * k; val hw = MARK_HEAD_W * k
        path.reset()
        path.moveTo((ex + tx * hl).toFloat(), (ey + ty * hl).toFloat())
        path.lineTo((ex - ty * hw).toFloat(), (ey + tx * hw).toFloat())
        path.lineTo((ex + ty * hw).toFloat(), (ey - tx * hw).toFloat())
        path.close()
        fill.color = dark; c.drawPath(path, fill)
        stroke.color = Color.BLACK; stroke.strokeWidth = edge * 1.5f; c.drawPath(path, stroke)
    }
}

/** The dance floor from above: the feet (FeetPainter), the title at the top left, the count at the top right, and the
 *  annotation banner at the top (the dark box, user 2026-10-26).
 *
 *  The camera (user 2026-10-19): the feet of a couple standing side by side fill about half the screen width; when the
 *  feet's bounding box gets close to an edge the view glides to centre it; when the box is too big for the screen the
 *  view zooms out just enough, and zooms back in once the box is small again. The part of the floor under the dance
 *  floor's bars (insetTop, insetBottom) does not count: the dancers stay between the bars. */
class FloorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        const val STANCE_BOX = 92.0      // cm: the width of two pairs of feet side by side, toes and arrows included
        const val EDGE = 0.10            // the box must stay out of this fraction of the visible floor at each edge
        const val BOX_MARGIN = 16.0      // cm around the foot centres for the silhouettes and arrows
        const val CAMERA_TAU = 0.225     // s: how fast the camera glides after the couple (user 2026-10-19: twice as fast as 0.45)
    }
    private val painter = FeetPainter(context)
    private val floorColor = context.getColor(R.color.floor)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ResourcesCompat.getFont(context, R.font.inter_bold) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var scene: Scene? = null
        set(v) { field = v; if (autoCamera) updateCamera(); invalidate() }
    /** world centre (cm) and px per cm */
    var cx = 10.0
    var cy = 20.0
    var scale = 4.0
    var autoCamera = true
    var showLabels = true
    /** px of the floor hidden under the bars at the top and the bottom */
    var insetTop = 0f
        set(v) { field = v; if (autoCamera) updateCamera(); invalidate() }
    var insetBottom = 0f
        set(v) { field = v; if (autoCamera) updateCamera(); invalidate() }
    private var baseScale = 0.0
    private var targetScale = 0.0
    private var targetCx = Double.NaN
    private var targetCy = Double.NaN
    private var tracking = false
    private var lastFrame = 0L

    private fun visibleH(): Float = max(1f, height - insetTop - insetBottom)
    private fun midY(): Float = insetTop + visibleH() / 2

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseScale = w / 2.0 / STANCE_BOX
        if (autoCamera) { scale = baseScale; targetScale = baseScale; scene?.let { snapTo(it) } }
    }

    /** put the camera straight on the scene's feet (no glide) */
    private fun snapTo(s: Scene) {
        val b = box(s) ?: return
        cx = (b[0] + b[2]) / 2; cy = (b[1] + b[3]) / 2; targetCx = cx; targetCy = cy
        scale = fitScale(b); targetScale = scale; tracking = false
        invalidate()
    }

    /** the feet's bounding box in cm: x0, y0, x1, y1 */
    private fun box(s: Scene): DoubleArray? {
        if (s.feet.isEmpty()) return null
        val xs = s.feet.values.map { it.x }; val ys = s.feet.values.map { it.y }
        return doubleArrayOf(xs.min() - BOX_MARGIN, ys.min() - BOX_MARGIN, xs.max() + BOX_MARGIN, ys.max() + BOX_MARGIN)
    }

    /** the base zoom, or less when the box does not fit inside the visible floor with its edge margins */
    private fun fitScale(b: DoubleArray): Double {
        if (baseScale <= 0 || width == 0) return scale
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val fit = min(width * (1 - 2 * EDGE) / max(1.0, w), visibleH() * (1 - 2 * EDGE) / max(1.0, h))
        return min(baseScale, fit)
    }

    private fun updateCamera() {
        val s = scene ?: return
        val b = box(s) ?: return
        if (width == 0) return
        if (targetCx.isNaN()) { snapTo(s); return }
        targetScale = fitScale(b)
        // does the box come close to an edge of the visible floor at the current zoom? then glide to centre it
        val my = midY(); val vh = visibleH()
        val left = width / 2 + (b[0] - cx) * scale; val rightEdge = width / 2 + (b[2] - cx) * scale
        val top = my - (b[3] - cy) * scale; val bottom = my - (b[1] - cy) * scale
        val near = left < width * EDGE || rightEdge > width * (1 - EDGE) || top < insetTop + vh * EDGE || bottom > insetTop + vh * (1 - EDGE)
        if (near || targetScale < scale * 0.999) tracking = true
        if (tracking) { targetCx = (b[0] + b[2]) / 2; targetCy = (b[1] + b[3]) / 2 }
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016 else ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.1)
        lastFrame = now
        val k = 1 - exp(-dt / CAMERA_TAU)
        cx += (targetCx - cx) * k; cy += (targetCy - cy) * k; scale += (targetScale - scale) * k
        val settled = abs(targetCx - cx) * scale < 1.5 && abs(targetCy - cy) * scale < 1.5 && abs(targetScale - scale) < 0.01
        if (settled) { cx = targetCx; cy = targetCy; scale = targetScale; tracking = false }
        else postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = scene
        if (autoCamera && s != null && lastFrame != 0L && box(s) != null) {
            // keep gliding between scene updates (a floor standing still still animates its camera)
            if (abs(targetCx - cx) * scale >= 1.5 || abs(targetCy - cy) * scale >= 1.5 || abs(targetScale - scale) >= 0.01) {
                val now = SystemClock.uptimeMillis(); val dt = ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.1); lastFrame = now
                val k = 1 - exp(-dt / CAMERA_TAU)
                cx += (targetCx - cx) * k; cy += (targetCy - cy) * k; scale += (targetScale - scale) * k
                postInvalidateOnAnimation()
            }
        }
        painter.ox = width / 2f; painter.oy = midY(); painter.cx = cx; painter.cy = cy; painter.scale = scale
        c.drawColor(floorColor)
        painter.grid(c, 0f, 0f, width.toFloat(), height.toFloat())
        if (s == null) return
        painter.feet(c, s.feet, s.weights)
        painter.arrows(c, s.arrows, s.feet, sight())   // over the shoes (user 2026-10-28)
        if (showLabels) labels(c, s)
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

    private fun labels(c: Canvas, s: Scene) {
        val w = width; val h = height
        text.textAlign = Paint.Align.LEFT; text.textSize = (h * 0.045f).coerceAtLeast(18f); text.color = s.titleColor ?: Color.rgb(34, 34, 34)
        c.drawText(s.title, h * 0.03f, h * 0.03f + text.textSize, text)
        text.textAlign = Paint.Align.RIGHT; text.textSize = (h * 0.11f).coerceAtLeast(32f); text.color = Color.rgb(34, 34, 34)
        c.drawText(s.count, w - h * 0.03f, h * 0.03f + text.textSize * 0.85f, text)
        val banner = s.banner
        if (!banner.isNullOrBlank()) {
            // the annotation as it always looked, at the top under the title and the count, out of the dancers' way (user 2026-10-26)
            text.textAlign = Paint.Align.CENTER; text.textSize = (h * 0.12f).coerceAtLeast(28f)
            val lines = banner.split('\n').flatMap { wrap(it, text, w * 0.9f) }
            val bh = lines.size * text.textSize * 1.15f
            val my = h * 0.15f + text.textSize * 0.5f + bh / 2   // the box's middle: its top just under the count
            fill.color = withAlpha(Color.rgb(17, 17, 24), 0.78)
            c.drawRoundRect(w * 0.04f, my - bh / 2 - text.textSize * 0.5f, w * 0.96f, my + bh / 2 + text.textSize * 0.3f, 24f, 24f, fill)
            text.color = context.getColor(R.color.accent)
            lines.forEachIndexed { i, ln -> c.drawText(ln, w / 2f, my - bh / 2 + text.textSize * (i + 0.9f) * 1.15f, text) }
        }
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (w in s.split(' ')) { val cand = if (line.isEmpty()) w else "$line $w"; if (p.measureText(cand) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = cand }
        if (line.isNotEmpty()) out.add(line); return out
    }
}
