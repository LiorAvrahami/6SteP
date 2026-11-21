package org.lior.sixstep

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
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
 *  there are, the shared DanceFloor which one to show, the shared HintPainter how it looks). This view lies over the
 *  floor and its bars and only draws: it takes no touches, so they reach what is under it. While a hint plays it is
 *  drawn at every frame; while it rests, nothing is drawn until it is to play again. */
class HintView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val surface = AndroidDraw()   // the canvas of the frame being drawn, as the shared painter wants it
    private var hint: Hint? = null
    private var since = 0L               // when it became the floor's hint
    /** how much of the floor the bottom bar covers now, in px */
    var bottomBar = 0f

    /** show this hint from now on (its picture waits a moment, plays, rests, plays again), or none */
    fun show(h: Hint?) {
        if (h == hint) return
        hint = h; since = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = hint ?: return
        val t = (SystemClock.uptimeMillis() - since) / 1000.0
        surface.canvas = canvas
        val playing = HintPainter.draw(surface, h, t, width.toDouble(), height.toDouble(), resources.displayMetrics.density.toDouble(), bottomBar.toDouble())
        surface.canvas = null
        if (playing) postInvalidateOnAnimation()
        else postInvalidateDelayed((HintPainter.wait(h, t) * 1000.0).toLong().coerceIn(16L, 60000L))
    }
}
