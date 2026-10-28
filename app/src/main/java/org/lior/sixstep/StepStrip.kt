package org.lior.sixstep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A strip of step pictures: one small floor per stop of a piece (its start position, then every instant a foot lands),
 *  side by side, dragged or flung sideways, with a scroll bar under them. The dance floor's bottom bar (every step of
 *  what is danced, the current one framed, the annotations pointing at the step where they start, a tap goes there) and
 *  the rows of the explore page (three pictures around the step that stands out most) are both one of these. */
class StepStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** one picture: the feet at a stop, their weights once settled, the count */
    class Cell(val feet: Map<String, Placement>, val weights: Map<Char, Map<Char, Double>>, val count: String)

    companion object {
        const val MARGIN = 14.0        // cm around the foot centres in a picture
        const val FOLLOW = 0.15        // how much of the way to the current step the strip glides each frame while playing
    }

    private val d = resources.displayMetrics.density
    var cells: List<Cell> = emptyList()
        set(v) { field = v; prepare(); invalidate() }
    /** (stop, text): an annotation that starts at that stop, drawn in the band above the pictures */
    var callouts: List<Pair<Int, String>> = emptyList()
        set(v) { field = v.sortedBy { it.first }; invalidate() }
    /** more than 0: this many pictures fill the width; otherwise each is cellWidthDp wide */
    var cellsAcross = 0f
    var cellWidthDp = 64f
    var gapDp = 0f
    var separators = false
    /** the transparent band above the pictures where the annotations are drawn (touches there go to what is underneath) */
    var calloutBandDp = 0f
    var barColor = Color.TRANSPARENT
    var current = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var onTap: ((Int) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null

    private val painter = FeetPainter(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold); textSize = 9.5f * d; color = Color.rgb(110, 110, 110)
    }
    private val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.inter_extrabold); textSize = 13f * d; color = Color.BLACK
    }
    private val path = Path()
    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocity: VelocityTracker? = null
    private var offset = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var lastTouch = 0L
    private var pendingCentre = -1
    private var centres = DoubleArray(0)
    private var boxW = DoubleArray(0)
    private var boxH = DoubleArray(0)
    private var pictureScale = 1.0

    /** the user is handling the strip (or just did): it does not follow the dance meanwhile */
    val busy: Boolean get() = dragging || !scroller.isFinished || SystemClock.uptimeMillis() - lastTouch < 2500

    private val padH get() = 8f * d
    private val padTop get() = 8f * d
    private val barGap get() = 5f * d
    private val barH get() = 4f * d
    private val padBottom get() = 5f * d
    private fun band() = calloutBandDp * d
    private fun gap() = gapDp * d
    private fun cellTop() = band() + padTop
    private fun cellH() = max(1f, height - cellTop() - barGap - barH - padBottom)
    private fun cellW() = if (cellsAcross > 0) max(1f, (width - 2 * padH - gap() * (cellsAcross - 1)) / cellsAcross) else cellWidthDp * d
    private fun pitch() = cellW() + gap()
    private fun maxOffset() = max(0f, 2 * padH + cells.size * pitch() - gap() - width)

    private fun prepare() {
        val n = cells.size
        centres = DoubleArray(2 * n); boxW = DoubleArray(n); boxH = DoubleArray(n)
        for ((k, c) in cells.withIndex()) {
            if (c.feet.isEmpty()) continue
            val xs = c.feet.values.map { it.x }; val ys = c.feet.values.map { it.y }
            centres[2 * k] = (xs.min() + xs.max()) / 2; centres[2 * k + 1] = (ys.min() + ys.max()) / 2
            boxW[k] = xs.max() - xs.min() + 2 * MARGIN; boxH[k] = ys.max() - ys.min() + 2 * MARGIN
        }
        if (width > 0) { pictureScale = fitPictures(); offset = offset.coerceIn(0f, maxOffset()) }
    }

    /** px per cm, the same for every picture: each one fits its cell, but the feet never get smaller than half the size
     *  of a couple side by side filling the cell */
    private fun fitPictures(): Double {
        val w = cellW() * 0.94; val h = cellH() * 0.94
        val natural = w / FloorView.STANCE_BOX
        var s = natural
        for (k in boxW.indices) if (boxW[k] > 0) s = min(s, min(w / boxW[k], h / boxH[k]))
        return max(s, natural * 0.5)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pictureScale = fitPictures()
        offset = offset.coerceIn(0f, maxOffset())
        if (pendingCentre >= 0) { val k = pendingCentre; pendingCentre = -1; scrollToCell(k, false) }
    }

    private fun targetFor(k: Int): Float = (padH + k * pitch() + cellW() / 2 - width / 2f).coerceIn(0f, maxOffset())

    /** bring picture k to the middle, gliding or at once */
    fun scrollToCell(k: Int, animate: Boolean) {
        if (width == 0) { pendingCentre = k; return }
        val target = targetFor(k)
        scroller.forceFinished(true)
        if (animate) { scroller.startScroll(offset.roundToInt(), 0, (target - offset).roundToInt(), 0, 350); postInvalidateOnAnimation() }
        else { offset = target; invalidate() }
    }

    /** keep the step being danced in the middle, gliding a little every frame; not while the user handles the strip */
    fun follow(k: Int) {
        if (busy || width == 0) return
        val target = targetFor(k)
        if (abs(target - offset) > 0.5f) { offset += ((target - offset) * FOLLOW).toFloat(); invalidate() }
    }

    private fun cellAt(x: Float): Int {
        val p = pitch(); val k = floor((x + offset - padH) / p).toInt()
        val inside = x + offset - padH - k * p
        return if (k in cells.indices && inside <= cellW()) k else -1
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        if (barColor != Color.TRANSPARENT) { paint.style = Paint.Style.FILL; paint.color = barColor; c.drawRect(0f, band(), w, height.toFloat(), paint) }
        val n = cells.size
        if (n == 0) return
        val cw = cellW(); val ch = cellH(); val ct = cellTop(); val p = pitch()
        val first = max(0, floor((offset - padH) / p).toInt())
        val last = min(n - 1, ceil((offset + w - padH) / p).toInt())
        for (k in first..last) {
            val l = padH + k * p - offset; val r = l + cw; val b = ct + ch
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE; c.drawRect(l, ct, r, b, paint)
            val cell = cells[k]
            c.save(); c.clipRect(l, ct, r, b)
            painter.ox = (l + r) / 2; painter.oy = (ct + b) / 2
            painter.cx = centres[2 * k]; painter.cy = centres[2 * k + 1]; painter.scale = pictureScale
            painter.grid(c, l, ct, r, b)
            painter.feet(c, cell.feet, cell.weights)
            c.restore()
            c.drawText(cell.count, l + 4 * d, ct + label.textSize + 2 * d, label)
            if (separators && k < n - 1) {
                paint.color = Color.rgb(128, 128, 128); paint.strokeWidth = d
                c.drawLine(r + gap() / 2, ct + 4 * d, r + gap() / 2, b - 4 * d, paint)
            }
            if (k == current) {
                paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.strokeWidth = 2.5f * d
                val inset = 1.25f * d
                c.drawRect(l + inset, ct + inset, r - inset, b - inset, paint)
            }
        }
        scrollBar(c, w)
        if (band() > 0f) drawCallouts(c, first, last)
    }

    private fun scrollBar(c: Canvas, w: Float) {
        val most = maxOffset(); if (most <= 0f) return
        val track = w - 2 * padH
        val thumb = max(24 * d, track * track / (track + most))
        val x = padH + (track - thumb) * (offset / most)
        val y = height - padBottom - barH
        paint.style = Paint.Style.FILL; paint.color = withAlpha(Color.rgb(156, 156, 156), 0.8)
        c.drawRoundRect(x, y, x + thumb, y + barH, barH / 2, barH / 2, paint)
    }

    /** the annotations: a khaki box with the text (cut short with "..." when long) and a pointer down to the picture of
     *  the step where it starts; one that would cover the box before it is left out */
    private fun drawCallouts(c: Canvas, first: Int, last: Int) {
        val p = pitch(); val cw = cellW(); val padX = 8 * d
        val boxBottom = band() - 6 * d; val boxTop = boxBottom - (note.textSize + 10 * d)
        val tip = cellTop() + 2 * d
        val fm = note.fontMetrics
        val baseline = (boxTop + boxBottom) / 2 - (fm.ascent + fm.descent) / 2
        var lastRight = Float.NEGATIVE_INFINITY
        for ((k, text) in callouts) {
            if (k < first || k > last) continue
            val ax = padH + k * p - offset + cw * 0.3f
            if (ax < 0f || ax > width) continue
            val maxW = max(cw * 2.6f, 140 * d)
            val t = TextUtils.ellipsize(text, note, maxW - 2 * padX, TextUtils.TruncateAt.END).toString()
            val bw = note.measureText(t) + 2 * padX
            val bl = min(max(ax - bw / 2, 2 * d), width - 2 * d - bw)
            if (bl < lastRight + 4 * d) continue
            lastRight = bl + bw
            path.reset(); path.moveTo(ax - 7 * d, boxBottom - d); path.lineTo(ax + 7 * d, boxBottom - d); path.lineTo(ax, tip); path.close()
            paint.style = Paint.Style.FILL; paint.color = Ui.NOTE; c.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.strokeWidth = 2 * d; c.drawPath(path, paint)
            paint.style = Paint.Style.FILL; paint.color = Ui.NOTE; c.drawRect(bl, boxTop, bl + bw, boxBottom, paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; c.drawRect(bl, boxTop, bl + bw, boxBottom, paint)
            c.drawText(t, bl + padX, baseline, note)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.y < band()) return false   // the annotations' band: the floor underneath gets the touch
                scroller.forceFinished(true); dragging = false
                downX = e.x; downY = e.y; lastX = e.x; lastTouch = now
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!dragging && abs(e.x - downX) > slop && abs(e.x - downX) > abs(e.y - downY)) {
                    dragging = true; lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onDragStart?.invoke()
                }
                if (dragging) { offset = (offset - (e.x - lastX)).coerceIn(0f, maxOffset()); lastX = e.x; invalidate() }
                lastTouch = now
            }
            MotionEvent.ACTION_UP -> {
                lastTouch = now
                if (dragging) {
                    velocity?.let { v ->
                        v.computeCurrentVelocity(1000)
                        scroller.fling(offset.roundToInt(), 0, -v.xVelocity.roundToInt(), 0, 0, maxOffset().roundToInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                } else {
                    val k = cellAt(e.x)
                    if (k >= 0) onTap?.invoke(k)
                    performClick()
                }
                dragging = false; velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; velocity?.recycle(); velocity = null }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { offset = scroller.currX.toFloat().coerceIn(0f, maxOffset()); postInvalidateOnAnimation() }
    }
}
