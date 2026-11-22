package org.lior.sixstep

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A strip of step pictures: one small floor per stop of a piece (its start position, then every instant a foot lands),
 *  side by side, with a scroll bar under them. The dance floor's bottom bar (every step of what is danced, the current
 *  one framed, the annotations pointing at the step where they start) and the rows of the explore page are both one of
 *  these. This is its layout and its drawing; the front owns the touches and the scrolling, and sets `offset`.
 *  Lengths given in dp are multiplied by `density` (1 on a web page). */
class StripPainter(private val density: Double) {
    companion object {
        const val MARGIN = 14.0        // cm around the foot centres in a picture
        const val FOLLOW = 0.15        // how much of the way to the current step the strip glides each frame while playing
    }

    private val d = density
    var cells: List<StepCell> = emptyList()
        set(v) { field = v; prepare() }
    /** (stop, text): an annotation that starts at that stop, drawn in the band above the pictures */
    var callouts: List<Pair<Int, String>> = emptyList()
        set(v) { field = v.sortedBy { it.first } }
    /** more than 0: this many pictures fill the width; otherwise each is cellWidthDp wide */
    var cellsAcross = 0.0
    var cellWidthDp = 64.0
    var gapDp = 0.0
    var separators = false
    /** the transparent band above the pictures where the annotations are drawn */
    var calloutBandDp = 0.0
    /** the bar behind the pictures, or null for none */
    var barColor: Int? = null
    var current = -1
    var width = 0.0
        private set
    var height = 0.0
        private set
    /** how far the strip is scrolled, in px; the front keeps it between 0 and maxOffset() */
    var offset = 0.0
    /** px the pictures are moved from `offset` as they are drawn: a hint's nudge (HintPainter.nudge), not a scroll */
    var nudge = 0.0

    private val painter = FeetPainter(density)
    /** one of the screen's own pixels, in the px this draws in (FeetPainter.pixel) */
    var pixel: Double
        get() = painter.pixel
        set(v) { painter.pixel = v }
    private var centres = DoubleArray(0)
    private var boxW = DoubleArray(0)
    private var boxH = DoubleArray(0)
    private var pictureScale = 1.0

    private val padH get() = 8 * d
    private val padTop get() = 8 * d
    private val barGap get() = 5 * d
    private val barH get() = 4 * d
    private val padBottom get() = 5 * d
    fun band(): Double = calloutBandDp * d
    private fun gap() = gapDp * d
    private fun cellTop() = band() + padTop
    private fun cellH() = max(1.0, height - cellTop() - barGap - barH - padBottom)
    private fun cellW() = if (cellsAcross > 0) max(1.0, (width - 2 * padH - gap() * (cellsAcross - 1)) / cellsAcross) else cellWidthDp * d
    private fun pitch() = cellW() + gap()
    fun maxOffset(): Double = max(0.0, 2 * padH + cells.size * pitch() - gap() - width)

    private fun prepare() {
        val n = cells.size
        centres = DoubleArray(2 * n); boxW = DoubleArray(n); boxH = DoubleArray(n)
        for ((k, c) in cells.withIndex()) {
            if (c.feet.isEmpty()) continue
            val xs = c.feet.values.map { it.x }; val ys = c.feet.values.map { it.y }
            centres[2 * k] = (xs.min() + xs.max()) / 2; centres[2 * k + 1] = (ys.min() + ys.max()) / 2
            boxW[k] = xs.max() - xs.min() + 2 * MARGIN; boxH[k] = ys.max() - ys.min() + 2 * MARGIN
        }
        if (width > 0) { pictureScale = fitPictures(); offset = offset.coerceIn(0.0, maxOffset()) }
    }

    /** px per cm, the same for every picture: each one fits its cell, but the feet never get smaller than half the size
     *  of a couple side by side filling the cell */
    private fun fitPictures(): Double {
        val w = cellW() * 0.94; val h = cellH() * 0.94
        val natural = w / FloorPicture.STANCE_BOX
        var s = natural
        for (k in boxW.indices) if (boxW[k] > 0) s = min(s, min(w / boxW[k], h / boxH[k]))
        return max(s, natural * 0.5)
    }

    fun setSize(w: Double, h: Double) {
        width = w; height = h
        pictureScale = fitPictures()
        offset = offset.coerceIn(0.0, maxOffset())
    }

    /** the offset that brings picture k to the middle */
    fun targetFor(k: Int): Double = (padH + k * pitch() + cellW() / 2 - width / 2).coerceIn(0.0, maxOffset())

    /** the picture at x (px from the strip's left edge), or -1 */
    fun cellAt(x: Double): Int {
        val p = pitch(); val k = floor((x + offset - padH) / p).toInt()
        val inside = x + offset - padH - k * p
        return if (k in cells.indices && inside <= cellW()) k else -1
    }

    fun draw(c: Draw) {
        val real = offset
        offset = real + nudge
        try { drawAt(c) } finally { offset = real }
    }

    private fun drawAt(c: Draw) {
        val w = width
        c.save(); c.clipRect(0.0, 0.0, w, height)
        barColor?.let { c.fillRect(0.0, band(), w, height, it) }
        val n = cells.size
        if (n == 0) { c.restore(); return }
        val cw = cellW(); val ch = cellH(); val ct = cellTop(); val p = pitch()
        val first = max(0, floor((offset - padH) / p).toInt())
        val last = min(n - 1, ceil((offset + w - padH) / p).toInt())
        val labelSize = 9.5 * d
        for (k in first..last) {
            val l = padH + k * p - offset; val r = l + cw; val b = ct + ch
            c.fillRect(l, ct, r, b, Colors.WHITE)
            val cell = cells[k]
            c.save(); c.clipRect(l, ct, r, b)
            painter.ox = (l + r) / 2; painter.oy = (ct + b) / 2
            painter.cx = centres[2 * k]; painter.cy = centres[2 * k + 1]; painter.scale = pictureScale
            painter.grid(c, l, ct, r, b)
            painter.feet(c, cell.feet, cell.weights)
            c.restore()
            c.text(cell.count, l + 4 * d, ct + labelSize + 2 * d, labelSize, Colors.LABEL, FontWeight.SEMIBOLD)
            if (separators && k < n - 1) c.line(r + gap() / 2, ct + 4 * d, r + gap() / 2, b - 4 * d, Colors.SEPARATOR, d)
            if (k == current) {
                val inset = 1.25 * d
                c.strokeRect(l + inset, ct + inset, r - inset, b - inset, Colors.BLACK, 2.5 * d)
            }
        }
        scrollBar(c, w)
        if (band() > 0) drawCallouts(c, first, last)
        c.restore()
    }

    private fun scrollBar(c: Draw, w: Double) {
        val most = maxOffset(); if (most <= 0) return
        val track = w - 2 * padH
        val thumb = max(24 * d, track * track / (track + most))
        val x = padH + (track - thumb) * (offset / most)
        val y = height - padBottom - barH
        c.fillRoundRect(x, y, x + thumb, y + barH, barH / 2, withAlpha(Colors.SCROLL, 0.8))
    }

    /** the annotations: a khaki box with the text (cut short with "…" when long) and a pointer down to the picture of
     *  the step where it starts; one that would cover the box before it is left out */
    private fun drawCallouts(c: Draw, first: Int, last: Int) {
        val p = pitch(); val cw = cellW(); val padX = 8 * d
        val noteSize = 13 * d
        val boxBottom = band() - 6 * d; val boxTop = boxBottom - (noteSize + 10 * d)
        val tip = cellTop() + 2 * d
        val baseline = (boxTop + boxBottom) / 2 + noteSize * 0.35
        var lastRight = Double.NEGATIVE_INFINITY
        for ((k, text) in callouts) {
            if (k < first || k > last) continue
            val ax = padH + k * p - offset + cw * 0.3
            if (ax < 0 || ax > width) continue
            val maxW = max(cw * 2.6, 140 * d)
            val t = ellipsize(c, text, noteSize, FontWeight.EXTRABOLD, maxW - 2 * padX)
            val bw = c.textWidth(t, noteSize, FontWeight.EXTRABOLD) + 2 * padX
            val bl = min(max(ax - bw / 2, 2 * d), width - 2 * d - bw)
            if (bl < lastRight + 4 * d) continue
            lastRight = bl + bw
            c.beginPath(); c.moveTo(ax - 7 * d, boxBottom - d); c.lineTo(ax + 7 * d, boxBottom - d); c.lineTo(ax, tip); c.closePath()
            c.fillPath(Colors.NOTE); c.strokePath(Colors.BLACK, 2 * d)
            c.fillRect(bl, boxTop, bl + bw, boxBottom, Colors.NOTE)
            c.strokeRect(bl, boxTop, bl + bw, boxBottom, Colors.BLACK, 2 * d)
            c.text(t, bl + padX, baseline, noteSize, Colors.BLACK, FontWeight.EXTRABOLD)
        }
    }
}
