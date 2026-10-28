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
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Arrow(val dancer: Char, val from: Placement, val to: Placement)

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
 *  grey silhouette; the pointed toe shows the heading), the schema view's dashed step arrows, the 50 cm grid.
 *  The one drawing of the dancers: the dance floor, the strips of step pictures and the launcher's little floor all use it. */
class FeetPainter(ctx: Context) {
    companion object { const val GRID = 50.0 }
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

    /** the schema view's dashed arrows, from where each foot was to where it landed */
    fun arrows(c: Canvas, arrows: List<Arrow>) {
        for (a in arrows) dashedArrow(c, if (a.dancer == 'L') leader else follower, a.from, a.to)
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

    private fun dashedArrow(c: Canvas, color: Int, from: Placement, to: Placement) {
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
        painter.arrows(c, s.arrows)
        painter.feet(c, s.feet, s.weights)
        if (showLabels) labels(c, s)
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
