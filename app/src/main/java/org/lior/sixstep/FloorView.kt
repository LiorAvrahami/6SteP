package org.lior.sixstep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
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

/** The dance floor from above: feet (opacity = weight over a grey base), heading arrows, bodies, arms, labels. */
class FloorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        const val FOOT_LEN = 26.0; const val FOOT_WID = 10.0; const val ARROW = 9.0
        const val SHOULDER = 18.0; const val ARM_MAX = 65.0; const val BODY_R = 10.0
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
        set(v) { field = v; invalidate() }
    var cx = 10.0; var cy = 20.0; var scale = 4.0   // world centre (cm) and px per cm

    fun px(x: Double, y: Double): PointF = PointF((width / 2 + (x - cx) * scale).toFloat(), (height / 2 - (y - cy) * scale).toFloat())

    /** centre the camera on a set of points with a margin, at a scale between the limits */
    fun fitTo(xs: List<Double>, ys: List<Double>, minScale: Double = 2.2, maxScale: Double = 6.0) {
        if (xs.isEmpty() || width == 0) return
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        cx = (x0 + x1) / 2; cy = (y0 + y1) / 2
        val s = min(width / (x1 - x0 + 110.0), height / (y1 - y0 + 110.0))
        scale = s.coerceIn(minScale, maxScale)
        invalidate()
    }

    private fun footPoly(p: Placement, side: Char): List<Pair<Double, Double>> {
        val f = fwd(p.h); val r = right(p.h); val s = if (side == 'R') 1.0 else -1.0
        val pts = ArrayList<Pair<Double, Double>>()
        val n = 10
        for (i in 0..n) { val a = PI * i / n; val c = cos(a)
            pts.add(Pair(c * FOOT_WID / 2 * (1 + 0.25 * (if (c * s < 0) 1 else 0)), FOOT_LEN / 2 - FOOT_WID / 2 + sin(a) * FOOT_WID / 2 * 1.15)) }
        for (i in 0..n) { val a = PI + PI * i / n
            pts.add(Pair(cos(a) * FOOT_WID / 2 * 0.85, -FOOT_LEN / 2 + FOOT_WID / 2 * 0.85 + sin(a) * FOOT_WID / 2 * 0.85)) }
        return pts.map { (a, b) -> Pair(p.x + a * r.first + b * f.first, p.y + a * r.second + b * f.second) }
    }

    private fun polyPath(pts: List<Pair<Double, Double>>): Path {
        path.reset()
        pts.forEachIndexed { i, (x, y) -> val q = px(x, y); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
        path.close(); return path
    }

    private fun withAlpha(color: Int, alpha: Double): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24)

    private fun drawFoot(c: Canvas, p: Placement, side: Char, color: Int, weight: Double, outlineOnly: Boolean = false, label: String? = null) {
        val poly = footPoly(p, side)
        val pp = polyPath(poly)
        if (outlineOnly) {
            stroke.color = withAlpha(color, 0.5); stroke.strokeWidth = 2f; stroke.pathEffect = null; c.drawPath(pp, stroke)
        } else {
            fill.color = withAlpha(grey, 0.2); c.drawPath(pp, fill)
            if (weight > 0.001) { fill.color = withAlpha(color, weight); c.drawPath(polyPath(poly), fill) }
        }
        // the arrow out of the toe
        val f = fwd(p.h); val r = right(p.h)
        val toe = Pair(p.x + f.first * FOOT_LEN / 2, p.y + f.second * FOOT_LEN / 2)
        val tip = Pair(toe.first + f.first * ARROW, toe.second + f.second * ARROW)
        val acol = withAlpha(if (weight > 0.5 || outlineOnly) color else grey, max(0.45, weight))
        stroke.color = acol; stroke.strokeWidth = max(2.0, scale * 1.4).toFloat(); stroke.pathEffect = null
        val a = px(toe.first, toe.second); val b = px(tip.first, tip.second)
        c.drawLine(a.x, a.y, b.x, b.y, stroke)
        val h1 = px(tip.first - f.first * 5 + r.first * 3.5, tip.second - f.second * 5 + r.second * 3.5)
        val h2 = px(tip.first - f.first * 5 - r.first * 3.5, tip.second - f.second * 5 - r.second * 3.5)
        path.reset(); path.moveTo(b.x, b.y); path.lineTo(h1.x, h1.y); path.lineTo(h2.x, h2.y); path.close()
        fill.color = acol; c.drawPath(path, fill)
        // L / R at the heel
        val hp = px(p.x - f.first * FOOT_LEN * 0.22, p.y - f.second * FOOT_LEN * 0.22)
        text.textSize = max(10.0, scale * 6.5).toFloat(); text.textAlign = Paint.Align.CENTER
        text.color = if (outlineOnly) withAlpha(color, 0.8) else withAlpha(Color.WHITE, min(1.0, weight + 0.35))
        c.drawText(side.toString(), hp.x, hp.y + text.textSize / 3, text)
        if (label != null) {
            val tp = px(p.x + f.first * (FOOT_LEN / 2 + ARROW + 7), p.y + f.second * (FOOT_LEN / 2 + ARROW + 7))
            text.color = color; text.textSize = max(11.0, scale * 5.5).toFloat(); c.drawText(label, tp.x, tp.y + text.textSize / 3, text)
        }
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

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(context.getColor(R.color.floor))
        // grid every 50 cm
        val g = 50.0; val halfW = width / scale / 2; val halfH = height / scale / 2
        var x = Math.floor((cx - halfW) / g) * g
        while (x < cx + halfW) { val p = px(x, 0.0); c.drawLine(p.x, 0f, p.x, height.toFloat(), gridPaint); x += g }
        var y = Math.floor((cy - halfH) / g) * g
        while (y < cy + halfH) { val p = px(0.0, y); c.drawLine(0f, p.y, width.toFloat(), p.y, gridPaint); y += g }
        val s = scene ?: return
        val bodies = HashMap<Char, Placement>()
        for (d in listOf('L', 'F')) {
            val a = s.feet["$d.L"] ?: continue; val b = s.feet["$d.R"] ?: continue
            val bx = (a.x + b.x) / 2; val by = (a.y + b.y) / 2
            val sx = sin(Math.toRadians(a.h)) + sin(Math.toRadians(b.h)); val sy = cos(Math.toRadians(a.h)) + cos(Math.toRadians(b.h))
            val body = Placement(bx, by, Math.toDegrees(Math.atan2(sx, sy)))
            bodies[d] = body
            val color = if (d == 'L') leader else follower
            val p = px(bx, by)
            fill.color = withAlpha(color, 0.12); c.drawCircle(p.x, p.y, (BODY_R * scale).toFloat(), fill)
            stroke.color = withAlpha(color, 0.5); stroke.strokeWidth = 1.5f; stroke.pathEffect = null; c.drawCircle(p.x, p.y, (BODY_R * scale).toFloat(), stroke)
            val f = fwd(body.h); val t = px(bx + f.first * 16, by + f.second * 16); c.drawLine(p.x, p.y, t.x, t.y, stroke)
        }
        drawHands(c, s.hands, bodies)
        for (ar in s.arrows) drawDashedArrow(c, if (ar.dancer == 'L') leader else follower, ar.from, ar.to)
        // weightless feet first so landed feet sit on top
        val order = s.feet.keys.sortedBy { k -> s.weights[k[0]]?.get(k[2]) ?: 0.0 }
        for (k in order) {
            val d = k[0]; val f = k[2]; val p = s.feet[k]!!
            drawFoot(c, p, f, if (d == 'L') leader else follower, s.weights[d]?.get(f) ?: 0.0)
        }
        // labels
        text.textAlign = Paint.Align.LEFT; text.textSize = (height * 0.045f).coerceAtLeast(18f); text.color = s.titleColor ?: Color.rgb(34, 34, 34)
        c.drawText(s.title, height * 0.03f, height * 0.03f + text.textSize, text)
        text.textAlign = Paint.Align.RIGHT; text.textSize = (height * 0.11f).coerceAtLeast(32f); text.isFakeBoldText = true; text.color = Color.rgb(34, 34, 34)
        c.drawText(s.count, width - height * 0.03f, height * 0.03f + text.textSize * 0.85f, text)
        text.isFakeBoldText = false
        if (!s.banner.isNullOrBlank()) {
            text.textAlign = Paint.Align.CENTER; text.textSize = (height * 0.12f).coerceAtLeast(28f); text.isFakeBoldText = true
            val lines = wrap(s.banner, text, width * 0.9f)
            val bh = lines.size * text.textSize * 1.15f
            fill.color = withAlpha(Color.rgb(17, 17, 24), 0.78); c.drawRoundRect(width * 0.04f, height / 2f - bh / 2 - text.textSize * 0.5f, width * 0.96f, height / 2f + bh / 2 + text.textSize * 0.3f, 24f, 24f, fill)
            text.color = context.getColor(R.color.accent)
            lines.forEachIndexed { i, ln -> c.drawText(ln, width / 2f, height / 2f - bh / 2 + text.textSize * (i + 0.9f) * 1.15f, text) }
            text.isFakeBoldText = false
        }
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (w in s.split(' ')) { val cand = if (line.isEmpty()) w else "$line $w"; if (p.measureText(cand) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = cand }
        if (line.isNotEmpty()) out.add(line); return out
    }
}
