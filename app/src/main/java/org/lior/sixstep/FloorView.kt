package org.lior.sixstep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Arrow(val dancer: Char, val from: Placement, val to: Placement)

/** Everything the floor shows at one instant. */
class Scene(
    val feet: Map<String, Placement>,
    val weights: Map<Char, Map<Char, Double>>,
    val hands: List<String>,
    val title: String,
    val count: String,
    val arrows: List<Arrow> = emptyList(),
    val banner: String? = null,
    val titleColor: Int? = null
)

/** The dance floor from above: feet drawn as shoe soles (opacity = weight over a grey base; the pointed toe shows the
 *  heading), bodies, arms, the dashed step arrows of the strategic view, labels.
 *
 *  The camera (user 2026-10-19): the feet of a couple standing side by side fill about half the screen width; when the
 *  feet's bounding box gets close to an edge the view glides to centre it; when the box is too big for the screen the
 *  view zooms out just enough, and zooms back in once the box is small again. */
class FloorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        const val FOOT_LEN = 26.0        // cm: the shoe from heel to toe (see `sole` and `heel`)
        const val SHOULDER = 18.0; const val ARM_MAX = 65.0; const val BODY_R = 10.0
        const val STANCE_BOX = 92.0      // cm: the width of two pairs of feet side by side, toes and arrows included
        const val EDGE = 0.10            // the box must stay out of this fraction of the screen at each edge
        const val BOX_MARGIN = 16.0      // cm around the foot centres for the silhouettes and arrows
        const val CAMERA_TAU = 0.225     // s: how fast the camera glides after the couple (user 2026-10-19: twice as fast as 0.45)
    }
    private val leader = context.getColor(R.color.leader)
    private val follower = context.getColor(R.color.follower)
    private val grey = Color.rgb(120, 120, 120)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 34, 34) }
    private val gridPaint = Paint().apply { color = context.getColor(R.color.floor_grid); strokeWidth = 1f }
    private val path = Path()

    var scene: Scene? = null
        set(v) { field = v; if (autoCamera) updateCamera(); invalidate() }
    /** world centre (cm) and px per cm */
    var cx = 10.0; var cy = 20.0; var scale = 4.0
    var autoCamera = true
    var showLabels = true
    /** not drawn for now (user 2026-10-19): the body circle with its heading line, and the arms (hands are a research project of their own) */
    var showBodies = false
    var showHands = false
    private var baseScale = 0.0
    private var targetScale = 0.0
    private var targetCx = Double.NaN; private var targetCy = Double.NaN
    private var tracking = false
    private var lastFrame = 0L

    fun px(x: Double, y: Double): PointF = PointF((width / 2 + (x - cx) * scale).toFloat(), (height / 2 - (y - cy) * scale).toFloat())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        baseScale = w / 2.0 / STANCE_BOX
        if (autoCamera) { scale = baseScale; targetScale = baseScale; scene?.let { snapTo(it) } }
    }

    /** put the camera straight on the scene's feet at the base zoom (no glide) */
    fun snapTo(s: Scene) {
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

    /** the base zoom, or less when the box does not fit inside the screen with its edge margins */
    private fun fitScale(b: DoubleArray): Double {
        if (baseScale <= 0 || width == 0) return scale
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val fit = min(width * (1 - 2 * EDGE) / max(1.0, w), height * (1 - 2 * EDGE) / max(1.0, h))
        return min(baseScale, fit)
    }

    private fun updateCamera() {
        val s = scene ?: return
        val b = box(s) ?: return
        if (width == 0) return
        if (targetCx.isNaN()) { snapTo(s); return }
        targetScale = fitScale(b)
        // does the box come close to an edge at the current zoom? then glide to centre it
        val left = width / 2 + (b[0] - cx) * scale; val rightEdge = width / 2 + (b[2] - cx) * scale
        val top = height / 2 - (b[3] - cy) * scale; val bottom = height / 2 - (b[1] - cy) * scale
        val near = left < width * EDGE || rightEdge > width * (1 - EDGE) || top < height * EDGE || bottom > height * (1 - EDGE)
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
        if (autoCamera && scene != null && lastFrame != 0L) {
            // keep gliding between scene updates (a static exploration view still animates its camera)
            val s = scene!!
            if (abs(targetCx - cx) * scale >= 1.5 || abs(targetCy - cy) * scale >= 1.5 || abs(targetScale - scale) >= 0.01) {
                val now = SystemClock.uptimeMillis(); val dt = ((now - lastFrame) / 1000.0).coerceIn(0.0, 0.1); lastFrame = now
                val k = 1 - exp(-dt / CAMERA_TAU)
                cx += (targetCx - cx) * k; cy += (targetCy - cy) * k; scale += (targetScale - scale) * k
                postInvalidateOnAnimation()
            }
            if (box(s) == null) return
        }
        drawScene(c, scene, width, height)
    }

    /** draw a scene with the current camera onto any canvas */
    fun drawScene(c: Canvas, s: Scene?, w: Int, h: Int) {
        c.drawColor(context.getColor(R.color.floor))
        val g = 50.0; val halfW = w / scale / 2; val halfH = h / scale / 2
        var x = Math.floor((cx - halfW) / g) * g
        while (x < cx + halfW) { val p = px(x, 0.0); c.drawLine(p.x, 0f, p.x, h.toFloat(), gridPaint); x += g }
        var y = Math.floor((cy - halfH) / g) * g
        while (y < cy + halfH) { val p = px(0.0, y); c.drawLine(0f, p.y, w.toFloat(), p.y, gridPaint); y += g }
        if (s == null) return
        val bodies = HashMap<Char, Placement>()
        for (d in listOf('L', 'F')) {
            val a = s.feet["$d.L"] ?: continue; val b = s.feet["$d.R"] ?: continue
            val bx = (a.x + b.x) / 2; val by = (a.y + b.y) / 2
            val sx = sin(Math.toRadians(a.h)) + sin(Math.toRadians(b.h)); val sy = cos(Math.toRadians(a.h)) + cos(Math.toRadians(b.h))
            val body = Placement(bx, by, Math.toDegrees(Math.atan2(sx, sy)))
            bodies[d] = body
            if (!showBodies) continue
            val color = if (d == 'L') leader else follower
            val p = px(bx, by)
            fill.color = withAlpha(color, 0.12); c.drawCircle(p.x, p.y, (BODY_R * scale).toFloat(), fill)
            stroke.color = withAlpha(color, 0.5); stroke.strokeWidth = 1.5f; stroke.pathEffect = null; c.drawCircle(p.x, p.y, (BODY_R * scale).toFloat(), stroke)
            val f = fwd(body.h); val t = px(bx + f.first * 16, by + f.second * 16); c.drawLine(p.x, p.y, t.x, t.y, stroke)
        }
        if (showHands) drawHands(c, s.hands, bodies)
        for (ar in s.arrows) drawDashedArrow(c, if (ar.dancer == 'L') leader else follower, ar.from, ar.to)
        val order = s.feet.keys.sortedBy { k -> s.weights[k[0]]?.get(k[2]) ?: 0.0 }
        for (k in order) {
            val d = k[0]; val f = k[2]; val p = s.feet[k]!!
            drawFoot(c, p, f, if (d == 'L') leader else follower, s.weights[d]?.get(f) ?: 0.0)
        }
        if (!showLabels) return
        text.textAlign = Paint.Align.LEFT; text.textSize = (h * 0.045f).coerceAtLeast(18f); text.color = s.titleColor ?: Color.rgb(34, 34, 34)
        c.drawText(s.title, h * 0.03f, h * 0.03f + text.textSize, text)
        text.textAlign = Paint.Align.RIGHT; text.textSize = (h * 0.11f).coerceAtLeast(32f); text.isFakeBoldText = true; text.color = Color.rgb(34, 34, 34)
        c.drawText(s.count, w - h * 0.03f, h * 0.03f + text.textSize * 0.85f, text)
        text.isFakeBoldText = false
        if (!s.banner.isNullOrBlank()) {
            // the annotation, as it always looked, but at the top, under the title and the count, instead of over the middle of
            // the floor where it hid the dancers (user 2026-10-26)
            text.textAlign = Paint.Align.CENTER; text.textSize = (h * 0.12f).coerceAtLeast(28f); text.isFakeBoldText = true
            val lines = wrap(s.banner, text, w * 0.9f)
            val bh = lines.size * text.textSize * 1.15f
            val cy = h * 0.15f + text.textSize * 0.5f + bh / 2   // the box's middle: its top just under the count
            fill.color = withAlpha(Color.rgb(17, 17, 24), 0.78); c.drawRoundRect(w * 0.04f, cy - bh / 2 - text.textSize * 0.5f, w * 0.96f, cy + bh / 2 + text.textSize * 0.3f, 24f, 24f, fill)
            text.color = context.getColor(R.color.accent)
            lines.forEachIndexed { i, ln -> c.drawText(ln, w / 2f, cy - bh / 2 + text.textSize * (i + 0.9f) * 1.15f, text) }
            text.isFakeBoldText = false
        }
    }

    /** the sole of a dancing shoe seen from below, in cm, for a LEFT foot: u across (+ towards the inner, big-toe side),
     *  v along (+ towards the toe), centred on the foot's placement and FOOT_LEN long. The front sole runs from a pointed
     *  toe to the waist; the heel is a separate piece behind a small gap (user 2026-10-19: "the bottom of a dancing shoe").
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

    private fun withAlpha(color: Int, alpha: Double): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24)
    /** a shoe: the grey silhouette, and the dancer's colour over it with the weight as its opacity */
    private fun drawFoot(c: Canvas, p: Placement, side: Char, color: Int, weight: Double) {
        val shoe = shoePath(p, side)
        fill.color = withAlpha(grey, 0.2); c.drawPath(shoe, fill)
        if (weight > 0.001) { fill.color = withAlpha(color, weight); c.drawPath(shoe, fill) }
    }

    private fun shoulder(b: Placement, side: Char): Pair<Double, Double> {
        val r = right(b.h); val k = if (side == 'R') SHOULDER else -SHOULDER
        return Pair(b.x + r.first * k, b.y + r.second * k)
    }
    private fun bodyPoint(b: Placement, which: String): Pair<Double, Double> {
        val f = fwd(b.h); val r = right(b.h)
        return when (which) {
            "back" -> Pair(b.x - f.first * 8, b.y - f.second * 8)
            "lshoulder" -> shoulder(b, 'L'); "rshoulder" -> shoulder(b, 'R')
            "lhip" -> Pair(b.x - r.first * 12, b.y - r.second * 12); "rhip" -> Pair(b.x + r.first * 12, b.y + r.second * 12)
            else -> Pair(b.x, b.y)
        }
    }
    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)

    private fun drawArm(c: Canvas, dancer: Char, side: Char, sh: Pair<Double, Double>, hand: Pair<Double, Double>, raised: Boolean, ok: Boolean) {
        val color = if (!ok) Color.rgb(220, 30, 30) else if (dancer == 'L') leader else follower
        val half = ARM_MAX / 2; val d = dist(sh, hand)
        val mx = (sh.first + hand.first) / 2; val my = (sh.second + hand.second) / 2
        val el = if (d < 1e-6) Pair(mx, my) else {
            val limit = sqrt(max(0.0, half * half - (d / 2) * (d / 2))); val off = min(0.22 * d + 4, limit)
            val ux = (hand.first - sh.first) / d; val uy = (hand.second - sh.second) / d; val sign = if (side == 'R') 1.0 else -1.0
            Pair(mx + uy * sign * off, my - ux * sign * off)
        }
        stroke.color = withAlpha(color, if (raised) 0.5 else 0.9); stroke.strokeWidth = max(2.0, scale * 1.8).toFloat(); stroke.pathEffect = null
        val a = px(sh.first, sh.second); val b = px(el.first, el.second); val h = px(hand.first, hand.second)
        c.drawLine(a.x, a.y, b.x, b.y, stroke); c.drawLine(b.x, b.y, h.x, h.y, stroke)
        fill.color = stroke.color; c.drawCircle(h.x, h.y, max(2.0, scale * 2.0).toFloat(), fill)
    }

    private fun drawHands(c: Canvas, hands: List<String>, bodies: Map<Char, Placement>) {
        for (conn in hands) {
            val raised = conn.contains('^'); val under = if (raised) conn.substringAfter('^') else ""
            val core = conn.substringBefore('^'); val parts = core.split('-'); if (parts.size != 2) continue
            val d1 = parts[0][0]; val h1 = parts[0][1]; val target = parts[1]
            val b1 = bodies[d1] ?: continue; val s1 = shoulder(b1, h1)
            if (target.length == 2 && (target[0] == 'L' || target[0] == 'F')) {
                val d2 = target[0]; val h2 = target[1]; val b2 = bodies[d2] ?: continue; val s2 = shoulder(b2, h2)
                val hand = if (under == "L" || under == "F") { val bu = bodies[under[0]]!!; Pair(bu.x, bu.y) } else Pair((s1.first + s2.first) / 2, (s1.second + s2.second) / 2)
                val ok = dist(s1, hand) <= ARM_MAX && dist(s2, hand) <= ARM_MAX
                drawArm(c, d1, h1, s1, hand, raised, ok); drawArm(c, d2, h2, s2, hand, raised, ok)
            } else {
                val d2 = if (d1 == 'L') 'F' else 'L'; val b2 = bodies[d2] ?: continue
                val hand = bodyPoint(b2, target)
                drawArm(c, d1, h1, s1, hand, raised, dist(s1, hand) <= ARM_MAX)
            }
        }
    }

    private fun drawDashedArrow(c: Canvas, color: Int, from: Placement, to: Placement) {
        val a = px(from.x, from.y); val b = px(to.x, to.y)
        val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()); if (len < 4) return
        stroke.color = withAlpha(color, 0.85); stroke.strokeWidth = max(2.0, scale * 1.2).toFloat()
        stroke.pathEffect = DashPathEffect(floatArrayOf((scale * 3).toFloat(), (scale * 2.2).toFloat()), 0f)
        c.drawLine(a.x, a.y, b.x, b.y, stroke)
        stroke.pathEffect = null
        val ux = (b.x - a.x) / len; val uy = (b.y - a.y) / len; val s = (scale * 4.5).toFloat()
        path.reset(); path.moveTo(b.x, b.y)
        path.lineTo((b.x - ux * s - uy * s * 0.55).toFloat(), (b.y - uy * s + ux * s * 0.55).toFloat())
        path.lineTo((b.x - ux * s + uy * s * 0.55).toFloat(), (b.y - uy * s - ux * s * 0.55).toFloat())
        path.close(); fill.color = withAlpha(color, 0.9); c.drawPath(path, fill)
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (w in s.split(' ')) { val cand = if (line.isEmpty()) w else "$line $w"; if (p.measureText(cand) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = cand }
        if (line.isNotEmpty()) out.add(line); return out
    }
}
