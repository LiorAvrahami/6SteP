package org.lior.sixstep

import kotlin.math.floor
import kotlin.math.min

/** What the dance floor can do that nothing on the screen says (user 2026-11-21: "People that use this app ... it's not
 *  intuitive to them, the things that they can do ... what I want is the most minimalistic addition to the interface
 *  that will make it so that people will engage with it in the ways that they can. ... a series of flags ... that
 *  indicate what the user has already done. And once a user has already used some functionality, then we don't need to
 *  hint at it anymore. If the user didn't use some functionality, then we keep hinting ... until they use" it).
 *
 *  Each of these is one such thing, with a flag of its own (`key`: the name the flag is kept under, see
 *  FloorHost.knows) and a small moving picture that shows the gesture (HintPainter). The dance floor shows one hint at
 *  a time, the one that fits what is on the screen, and only while its flag is down (DanceFloor.hintNow). Doing the
 *  thing once raises the flag for good. */
enum class Hint(val key: String) {
    /** a tap on the floor pauses the dance */
    TAP_PAUSE("tap_pause"),
    /** a tap on the floor plays it */
    TAP_PLAY("tap_play"),
    /** a swipe down on the floor brings the top and the bottom bar */
    SWIPE_DOWN("bars"),
    /** a tap on a step of the bottom bar goes to that step */
    STRIP_TAP("strip_tap"),
    /** the bottom bar can be dragged to the left and to the right */
    STRIP_SCROLL("strip_scroll");

    /** the name its flag is kept under, in the app's preferences and in the browser's storage */
    val flag: String get() = flagOf(key)

    companion object {
        fun flagOf(key: String): String = "hint_$key"
    }
}

/** The hints' pictures, the same in the Android app and on the web page. Their one sign is a fingertip: a round dot.
 *  A dot with three rings going out from it, one after the other, is a tap (the user's own idea: "3 consecutive circles
 *  that appear that indicate that you tap"); a mark inside it says what the tap does (pause, play). A dot that slides,
 *  with a tail behind it, is a swipe.
 *
 *  A hint is one quick animation, a swipe two of them (user 2026-11-21, on the first version, whose pictures took a
 *  second or two: "kind of annoying because their animations are slow. Make the animations like at least 4 or 5 times
 *  snappier ... every hint is one animation ... for a swipe ... two animations ... Very snappy, very quick. And then
 *  you make the hint come back like every 4 seconds, never give up until the user does the gesture"). So: it waits
 *  before it first shows (delay), flashes, and comes again every EVERY seconds for as long as it is the floor's hint.
 *
 *  Everything is drawn in the dark grey of the floor's own words, half see-through, so that it lies over the dance
 *  without hiding it. Lengths are in dp; `dp` is one of them in the canvas's own pixels. */
object HintPainter {
    /** seconds before a hint first shows, counted from when it became the floor's hint */
    fun delay(hint: Hint): Double = when (hint) {
        Hint.TAP_PAUSE -> 3.0      // first the dance is watched
        Hint.TAP_PLAY -> 4.0       // whoever paused did so to look (user 2026-11-21: "wait a few seconds ... before you hint them to play")
        Hint.SWIPE_DOWN -> 2.0
        Hint.STRIP_TAP -> 1.2      // the bars have slid in by then
        Hint.STRIP_SCROLL -> 1.2
    }
    const val EVERY = 4.0          // s from one showing of a hint to its next
    const val TAP_PLAYS = 0.42     // s: a tap's one animation, from its first moment to its last
    const val RINGS = 3            // the circles of a tap
    const val RING_EVERY = 0.06    // s between two of them
    const val RING_LASTS = 0.24    // s: a ring from the dot's edge to gone
    const val SLIDES = 2           // a swipe is shown twice in a row
    const val SLIDE = 0.3          // s: one of them
    const val SLIDE_GAP = 0.06     // s between the two
    const val SWIPE_PLAYS = SLIDES * SLIDE + (SLIDES - 1) * SLIDE_GAP

    private fun ink(alpha: Double): Int = withAlpha(Colors.INK, alpha)
    private fun smooth(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }
    /** how much there is of a picture `u` seconds into its showing: rising to all of it by `full`, staying, falling
     *  from `fade` to nothing at `end` */
    private fun envelope(u: Double, full: Double, fade: Double, end: Double): Double =
        if (u <= 0.0 || u >= end) 0.0 else min(smooth(u / full), 1.0 - smooth((u - fade) / (end - fade)))

    /** where a hint's picture is, so that a screen can also tell without drawing: the centre of a tap, or the two ends
     *  of a swipe. `w`, `h`: the floor's whole size; `bottomBar`: how much of it the bottom bar covers now. */
    fun place(hint: Hint, w: Double, h: Double, dp: Double, bottomBar: Double): DoubleArray = when (hint) {
        // where a right thumb rests on a phone held sideways: beside the dancers, not on them
        Hint.TAP_PAUSE, Hint.TAP_PLAY -> doubleArrayOf(w - 104 * dp, h - bottomBar - 88 * dp)
        Hint.SWIPE_DOWN -> doubleArrayOf(w / 2, 22 * dp, w / 2, 112 * dp)
        // on the row of step pictures of the bottom bar (the bar is the lower 100 dp of its 132)
        Hint.STRIP_TAP -> doubleArrayOf(w * 0.64, h - 56 * dp)
        Hint.STRIP_SCROLL -> doubleArrayOf(w * 0.64, h - 56 * dp, w * 0.40, h - 56 * dp)
    }

    private fun swipes(hint: Hint) = hint == Hint.SWIPE_DOWN || hint == Hint.STRIP_SCROLL
    /** how long one showing of the hint takes, in seconds */
    fun plays(hint: Hint): Double = if (swipes(hint)) SWIPE_PLAYS else TAP_PLAYS

    /** how many seconds from `t` (counted as in draw) until the hint's picture next plays; 0 while it plays. A screen
     *  need not draw anything until then. */
    fun wait(hint: Hint, t: Double): Double {
        val since = t - delay(hint)
        if (since < 0) return -since
        val u = since - floor(since / EVERY) * EVERY
        return if (u < plays(hint)) 0.0 else EVERY - u
    }

    /** Draw `hint` as it looks `t` seconds after it became the floor's hint. True while it plays (false: nothing was
     *  drawn, it is waiting or resting). */
    fun draw(d: Draw, hint: Hint, t: Double, w: Double, h: Double, dp: Double, bottomBar: Double): Boolean {
        val since = t - delay(hint)
        if (since < 0) return false
        val u = since - floor(since / EVERY) * EVERY
        if (u >= plays(hint)) return false
        val at = place(hint, w, h, dp, bottomBar)
        when (hint) {
            Hint.TAP_PAUSE, Hint.TAP_PLAY -> tap(d, at[0], at[1], u, 17 * dp, 32 * dp, dp, hint)
            Hint.STRIP_TAP -> tap(d, at[0], at[1], u, 9 * dp, 24 * dp, dp, null)
            Hint.SWIPE_DOWN, Hint.STRIP_SCROLL -> {
                val n = floor(u / (SLIDE + SLIDE_GAP)).toInt()      // which of the slides
                val v = u - n * (SLIDE + SLIDE_GAP)                 // how far into it; past SLIDE: between two
                if (hint == Hint.SWIPE_DOWN) {
                    pull(d, at[0], at[3], u, v, dp)
                    if (v < SLIDE) swipe(d, at[0], at[1], at[2], at[3], v, 12 * dp)
                } else if (v < SLIDE) {
                    // to the left, then to the right: the bar goes both ways
                    if (n % 2 == 1) swipe(d, at[2], at[3], at[0], at[1], v, 9 * dp) else swipe(d, at[0], at[1], at[2], at[3], v, 9 * dp)
                }
            }
        }
        return true
    }

    /** a tap at (cx, cy), `u` seconds into its showing: the fingertip (a dot of radius r), three rings that leave it one
     *  after the other and grow by `reach` as they fade, and inside the dot what the tap does */
    private fun tap(d: Draw, cx: Double, cy: Double, u: Double, r: Double, reach: Double, dp: Double, what: Hint?) {
        val a = envelope(u, 0.04, TAP_PLAYS - 0.1, TAP_PLAYS)
        for (k in 0 until RINGS) {
            val v = (u - 0.02 - k * RING_EVERY) / RING_LASTS
            if (v <= 0.0 || v >= 1.0) continue
            val out = 1 - (1 - v) * (1 - v)   // quick at first, slowing: as a ring on water
            d.strokeArc(cx, cy, r + reach * out, 0.0, 360.0, ink(0.55 * (1 - v)), 2.5 * dp)
        }
        d.fillRoundRect(cx - r, cy - r, cx + r, cy + r, r, ink(0.55 * a))
        val white = withAlpha(Colors.WHITE, 0.95 * a)
        when (what) {
            Hint.TAP_PAUSE -> {   // two upright bars
                val bw = r * 0.26; val bh = r * 0.84; val gap = r * 0.2
                d.fillRoundRect(cx - gap - bw, cy - bh / 2, cx - gap, cy + bh / 2, bw * 0.3, white)
                d.fillRoundRect(cx + gap, cy - bh / 2, cx + gap + bw, cy + bh / 2, bw * 0.3, white)
            }
            Hint.TAP_PLAY -> {    // a triangle pointing right, a little right of the middle, where it looks centred
                val s = r * 0.5
                d.beginPath(); d.moveTo(cx - s * 0.62, cy - s); d.lineTo(cx + s * 0.95, cy); d.lineTo(cx - s * 0.62, cy + s); d.closePath()
                d.fillPath(white)
            }
            else -> {}
        }
    }

    /** what a swipe down pulls: at the top edge a short grip, as on anything that slides out, there for the whole
     *  showing (`u` seconds into it); and under where the finger ends an arrowhead pointing down, which comes each time
     *  the finger gets there (`v` seconds into the slide) */
    private fun pull(d: Draw, x: Double, y1: Double, u: Double, v: Double, dp: Double) {
        val a = envelope(u, 0.04, SWIPE_PLAYS - 0.07, SWIPE_PLAYS)
        d.fillRoundRect(x - 24 * dp, 5 * dp, x + 24 * dp, 10 * dp, 2.5 * dp, ink(0.42 * a))
        val b = envelope(v, 0.04, SLIDE - 0.07, SLIDE) * smooth((v - 0.1) / 0.08)
        if (b > 0.01) {
            val cy = y1 + 26 * dp; val half = 11 * dp
            d.beginPath(); d.moveTo(x - half, cy - half * 0.6); d.lineTo(x, cy + half * 0.4); d.lineTo(x + half, cy - half * 0.6)
            d.strokePath(ink(0.55 * b), 3.5 * dp)
        }
    }

    /** one slide of a swipe from (x0, y0) to (x1, y1), `v` seconds into it: the fingertip (a dot of radius r) comes up,
     *  slides across with a tail behind it that runs out to a point, and goes */
    private fun swipe(d: Draw, x0: Double, y0: Double, x1: Double, y1: Double, v: Double, r: Double) {
        val a = envelope(v, 0.04, SLIDE - 0.07, SLIDE)
        val f = smooth((v - 0.03) / (SLIDE - 0.11))    // how far along the dot is
        val x = x0 + (x1 - x0) * f; val y = y0 + (y1 - y0) * f
        // the tail: behind the dot, over the last part of the way, running out to a point. It starts on the dot's back
        // edge, as wide as the dot is there, so that the two see-through shapes do not lie on each other (one shape, and
        // so evenly see-through)
        val tail = min(f, 0.55)
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        if (tail * len > r * 1.2) {
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len      // along the way
            val bx = x - ux * r * 0.75; val by = y - uy * r * 0.75   // on the dot, three quarters of the way to its back
            val half = r * 0.66                                       // half the dot's width there
            d.beginPath(); d.moveTo(bx - uy * half, by + ux * half); d.lineTo(x - ux * len * tail, y - uy * len * tail); d.lineTo(bx + uy * half, by - ux * half); d.closePath()
            d.fillPath(ink(0.3 * a))
        }
        d.fillRoundRect(x - r, y - r, x + r, y + r, r, ink(0.55 * a))
    }
}
