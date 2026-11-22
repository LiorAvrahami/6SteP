package org.lior.sixstep

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View

/** Android's Canvas behind the small drawing interface of the shared painters (Draw; the web page's is CanvasDraw).
 *  Made for the hints (2026-11-21), the first thing the app draws with a shared painter: what the web page shows is
 *  then what the phone shows. Lengths are the canvas's own pixels, colours 0xAARRGGBB, as the interface says; paths
 *  and arcs are stroked with round ends and corners, plain lines and rectangles with square ones. */
class AndroidDraw : Draw {
    /** the canvas to draw on, for the length of one onDraw */
    var canvas: Canvas? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    private fun pen(color: Int, width: Double, round: Boolean): Paint {
        stroke.color = color; stroke.strokeWidth = width.toFloat(); stroke.pathEffect = null
        stroke.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
        stroke.strokeJoin = if (round) Paint.Join.ROUND else Paint.Join.MITER
        return stroke
    }
    private fun paint(color: Int): Paint { fill.color = color; return fill }

    override fun fillAll(color: Int) { canvas?.drawColor(color) }
    override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) {
        canvas?.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), pen(color, width, false))
    }
    override fun fillRect(l: Double, t: Double, r: Double, b: Double, color: Int) {
        canvas?.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint(color))
    }
    override fun strokeRect(l: Double, t: Double, r: Double, b: Double, color: Int, width: Double) {
        canvas?.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), pen(color, width, false))
    }
    override fun fillRoundRect(l: Double, t: Double, r: Double, b: Double, radius: Double, color: Int) {
        val rad = minOf(radius, (r - l) / 2, (b - t) / 2).toFloat()
        box.set(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        canvas?.drawRoundRect(box, rad, rad, paint(color))
    }

    override fun beginPath() { path.rewind() }
    override fun moveTo(x: Double, y: Double) { path.moveTo(x.toFloat(), y.toFloat()) }
    override fun lineTo(x: Double, y: Double) { path.lineTo(x.toFloat(), y.toFloat()) }
    override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        path.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x3.toFloat(), y3.toFloat())
    }
    override fun closePath() { path.close() }
    override fun fillPath(color: Int) { canvas?.drawPath(path, paint(color)) }
    override fun strokePath(color: Int, width: Double, dash: DoubleArray?) {
        val p = pen(color, width, true)
        if (dash != null && dash.size >= 2) p.pathEffect = DashPathEffect(floatArrayOf(dash[0].toFloat(), dash[1].toFloat()), 0f)
        canvas?.drawPath(path, p)
        p.pathEffect = null
    }
    override fun strokeArc(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double, color: Int, width: Double) {
        box.set((cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat())
        canvas?.drawArc(box, startDeg.toFloat(), sweepDeg.toFloat(), false, pen(color, width, true))
    }

    override fun save() { canvas?.save() }
    override fun clipRect(l: Double, t: Double, r: Double, b: Double) { canvas?.clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()) }
    override fun restore() { canvas?.restore() }

    override fun text(s: String, x: Double, y: Double, size: Double, color: Int, weight: FontWeight, align: TextAlign) {
        words.textSize = size.toFloat(); words.color = color; words.isFakeBoldText = weight != FontWeight.SEMIBOLD
        words.textAlign = when (align) { TextAlign.LEFT -> Paint.Align.LEFT; TextAlign.CENTER -> Paint.Align.CENTER; TextAlign.RIGHT -> Paint.Align.RIGHT }
        canvas?.drawText(s, x.toFloat(), y.toFloat(), words)
    }
    override fun textWidth(s: String, size: Double, weight: FontWeight): Double {
        words.textSize = size.toFloat(); words.isFakeBoldText = weight != FontWeight.SEMIBOLD
        return words.measureText(s).toDouble()
    }
}

/** The dance floor's hints: small moving pictures of the gestures a user has not used yet (the shared Hint says which
 *  there are, the shared DanceFloor which one to show and when the user touched the screen, the shared HintPainter how
 *  it looks and when it plays). This view lies over the floor and its bars and only draws: it takes no touches, so they
 *  reach what is under it. While a hint plays it is drawn at every frame, and `onNudge` is told how far the real
 *  interface follows its finger (HintPainter.nudge: the top bar peeks in, the bottom bar's pictures move); while it
 *  rests nothing is drawn until it is to play again, and after its last showing nothing at all until the next touch. */
class HintView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val surface = AndroidDraw()   // the canvas of the frame being drawn, as the shared painter wants it
    private var hint: Hint? = null
    private var since = 0L               // when its clock started: when it became the floor's hint, or the last touch
    private var still = false            // the phone asks for less motion (its animations are switched off)
    private var first = 0.0              // s from `since` to its first showing (the floor decides: HintPainter.firstAt, AFTER_PAUSE)
    /** how much of the floor the bottom bar covers now, in px */
    var bottomBar = 0f
    /** told at every frame of a hint how far the interface follows its finger (HintPainter.nudge), and 0 when it stops */
    var onNudge: ((Hint?, Double) -> Unit)? = null

    private val tick = Runnable { frame() }

    /** show this hint from now on, its clock started afresh (it waits, plays, rests, plays again, and after its last
     *  showing waits for the next touch), or none; `first`: seconds from now to its first showing */
    fun show(h: Hint?, first: Double) {
        hint = h; since = SystemClock.uptimeMillis(); this.first = first
        still = try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
        removeCallbacks(tick)
        onNudge?.invoke(h, 0.0)
        invalidate()
        if (h != null) post(tick)
    }

    private fun seconds(): Double = (SystemClock.uptimeMillis() - since) / 1000.0

    /** one frame: the interface's nudge, a new picture, and when the next frame is due */
    private fun frame() {
        val h = hint ?: return
        val t = seconds()
        onNudge?.invoke(h, HintPainter.nudge(h, t, still, first))
        invalidate()
        val wait = HintPainter.wait(h, t, first)
        if (wait == 0.0) postOnAnimation(tick)
        else if (wait.isFinite()) postDelayed(tick, (wait * 1000.0).toLong().coerceIn(16L, 60000L))
    }

    override fun onDraw(canvas: Canvas) {
        val h = hint ?: return
        surface.canvas = canvas
        HintPainter.draw(surface, h, seconds(), width.toDouble(), height.toDouble(), resources.displayMetrics.density.toDouble(), bottomBar.toDouble(), still, first)
        surface.canvas = null
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
