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

/** The hints' pictures, the same in the Android app and on the web page, made after the common practice for gesture
 *  hints (looked up on 2026-11-22, when the user found v72's flashes "really annoying ... I guess they move too fast"
 *  and v71's 1.5 to 1.9 s pictures had been "slow"):
 *
 *  - Each movement takes 200 to 500 ms, eased: NN/g ("most animations should fall in the 100–500 ms range, depending
 *    on complexity and how far the element travels") and Google's web.dev (ease-outs and ease-ins of 200 to 500 ms).
 *    v72's slides were 0.19 s of movement: a flick, too quick to follow. So a fingertip comes (0.18 s), presses down
 *    (0.12 s), moves or lets go, and fades: one tap about 1 s, one swipe stroke 0.8 s with 0.45 s of movement.
 *  - The real interface makes a little of the move too, so that the hint shows what the gesture does: Material's
 *    gesture education plays "a partial version of the gesture" behind its touch indicator, and Microsoft's swipe hint
 *    moves the item itself a short way (15 px vertically, 23 px horizontally). So the top bar peeks in by BAR_PEEK dp
 *    while the finger swipes down, and the bottom bar's pictures follow the finger by STRIP_NUDGE dp (nudge: the
 *    screens move them).
 *  - A hint never plays while the user is busy: it waits until the screen has been left alone (delay, counted from
 *    the last touch: DanceFloor.touched restarts it), any touch makes it go at once, and it shows SHOWINGS times, GAP
 *    seconds apart, and then rests until the user touches the screen again. Material: education only for users who
 *    have not done the gesture yet, on the first run and in later sessions for those who still have not; the hints
 *    found in apps and libraries are triggered by idleness, cancelled by a touch, and capped at a few plays.
 *  - Where the phone asks for less motion, a still picture fades in and out instead (still): NN/g and the design
 *    systems that were looked up ask to respect "reduce motion".
 *
 *  Their one sign is a fingertip: a round dot. A dot with three rings going out from it is a tap (the user's own idea:
 *  "3 consecutive circles that appear that indicate that you tap"); a mark inside it says what the tap does (pause,
 *  play). A dot that slides is a swipe; a swipe is shown twice in a row (the user, 2026-11-21: "Two animations of a
 *  swipe"). Everything is drawn in the dark grey of the floor's own words, half see-through, so that it lies over the
 *  dance without hiding it. Lengths are in dp; `dp` is one of them in the canvas's own pixels. */
object HintPainter {
    /** seconds without a touch before a hint first shows: from when it became the floor's hint, or from the user's
     *  last touch, whichever came later */
    fun delay(hint: Hint): Double = when (hint) {
        Hint.TAP_PAUSE -> 4.0      // first the dance is watched
        Hint.TAP_PLAY -> 5.0       // whoever paused did so to look (user 2026-11-21: "wait a few seconds ... before you hint them to play")
        Hint.SWIPE_DOWN -> 3.0
        Hint.STRIP_TAP -> 2.5      // the bars have just come
        Hint.STRIP_SCROLL -> 2.5
    }
    const val SHOWINGS = 3         // showings in a row; then nothing until the user touches the screen (or another hint takes over)
    const val GAP = 8.0            // s from the start of one showing to the start of the next

    // a tap: the fingertip comes, presses and lets go; three rings go out from where it pressed; it fades
    const val APPEAR = 0.18        // s: the fingertip comes (fading in, settling from a little larger)
    const val PRESS = 0.12         // s: it presses down (a little smaller and darker), then lets go
    const val RINGS = 3
    const val RING_EVERY = 0.15    // s between two rings
    const val RING_LASTS = 0.5     // s: a ring from the fingertip's edge to gone, slowing as it goes
    const val TAP_PLAYS = 1.05     // s: the whole tap, its fading included

    // a swipe: per stroke, the fingertip comes, presses, slides, lets go and fades; twice
    const val STROKES = 2
    const val STROKE = 0.8         // s: one stroke
    const val STROKE_GAP = 0.15    // s between the two
    const val MOVE_FROM = 0.2      // s into a stroke: the slide begins
    const val MOVE = 0.45          // s: the slide, slow at both ends
    const val SWIPE_PLAYS = STROKES * STROKE + (STROKES - 1) * STROKE_GAP

    // how far the real interface follows the finger (nudge), in dp
    const val BAR_PEEK = 18.0      // the top bar comes down this far during the swipe down
    const val STRIP_NUDGE = 32.0   // the bottom bar's pictures follow a drag this far

    private fun ink(alpha: Double): Int = withAlpha(Colors.INK, alpha)
    private fun smooth(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }
    private fun easeOut(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return 1 - (1 - u) * (1 - u) * (1 - u) }
    private fun easeInOut(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return if (u < 0.5) 4 * u * u * u else 1 - (2 - 2 * u).let { it * it * it } / 2 }

    /** where a hint's picture is, so that a screen can also tell without drawing: the centre of a tap, or the two ends
     *  of a swipe. `w`, `h`: the floor's whole size; `bottomBar`: how much of it the bottom bar covers now. */
    fun place(hint: Hint, w: Double, h: Double, dp: Double, bottomBar: Double): DoubleArray = when (hint) {
        // where a right thumb rests on a phone held sideways: beside the dancers, not on them
        Hint.TAP_PAUSE, Hint.TAP_PLAY -> doubleArrayOf(w - 104 * dp, h - bottomBar - 88 * dp)
        Hint.SWIPE_DOWN -> doubleArrayOf(w / 2, 26 * dp, w / 2, 116 * dp)
        // on the row of step pictures of the bottom bar (the bar is the lower 100 dp of its 132)
        Hint.STRIP_TAP -> doubleArrayOf(w * 0.64, h - 56 * dp)
        Hint.STRIP_SCROLL -> doubleArrayOf(w * 0.64, h - 56 * dp, w * 0.40, h - 56 * dp)
    }

    private fun swipes(hint: Hint) = hint == Hint.SWIPE_DOWN || hint == Hint.STRIP_SCROLL
    /** how long one showing of the hint takes, in seconds */
    fun plays(hint: Hint): Double = if (swipes(hint)) SWIPE_PLAYS else TAP_PLAYS

    /** which showing `t` seconds fall in, and how far into it they are; null between showings and after the last */
    private fun showing(hint: Hint, t: Double): Pair<Int, Double>? {
        val since = t - delay(hint)
        if (since < 0) return null
        val n = floor(since / GAP).toInt()
        if (n >= SHOWINGS) return null
        val u = since - n * GAP
        return if (u < plays(hint)) Pair(n, u) else null
    }

    /** how many seconds from `t` (counted as in draw) until the hint's picture next plays: 0 while it plays, infinite
     *  after its last showing (it comes back when the user touches the screen). A screen need not draw anything until
     *  then. */
    fun wait(hint: Hint, t: Double): Double {
        val since = t - delay(hint)
        if (since < 0) return -since
        val n = floor(since / GAP).toInt()
        if (n >= SHOWINGS) return Double.POSITIVE_INFINITY
        val u = since - n * GAP
        if (u < plays(hint)) return 0.0
        return if (n + 1 >= SHOWINGS) Double.POSITIVE_INFINITY else GAP - u
    }

    /** Draw `hint` as it looks `t` seconds after it became the floor's hint (or after the user's last touch). True
     *  while it plays (false: nothing was drawn, it is waiting or resting). `still`: the phone asks for less motion. */
    fun draw(d: Draw, hint: Hint, t: Double, w: Double, h: Double, dp: Double, bottomBar: Double, still: Boolean = false): Boolean {
        val (_, u) = showing(hint, t) ?: return false
        val at = place(hint, w, h, dp, bottomBar)
        when (hint) {
            Hint.TAP_PAUSE, Hint.TAP_PLAY -> if (still) tapStill(d, at[0], at[1], u, 18 * dp, 30 * dp, dp, hint) else tap(d, at[0], at[1], u, 18 * dp, 30 * dp, dp, hint)
            Hint.STRIP_TAP -> if (still) tapStill(d, at[0], at[1], u, 10 * dp, 22 * dp, dp, null) else tap(d, at[0], at[1], u, 10 * dp, 22 * dp, dp, null)
            Hint.SWIPE_DOWN, Hint.STRIP_SCROLL -> {
                val both = hint == Hint.STRIP_SCROLL
                val r = (if (both) 10 else 12) * dp
                if (still) swipeStill(d, at[0], at[1], at[2], at[3], u, r, dp, both)
                else {
                    val k = floor(u / (STROKE + STROKE_GAP)).toInt()   // which stroke
                    val v = u - k * (STROKE + STROKE_GAP)               // how far into it; past STROKE: between two
                    // the bottom bar's goes to the left, then to the right: it can be dragged both ways
                    if (v < STROKE) {
                        if (both && k % 2 == 1) stroke(d, at[2], at[3], at[0], at[1], v, r, dp) else stroke(d, at[0], at[1], at[2], at[3], v, r, dp)
                    }
                }
            }
        }
        return true
    }

    /** How far the real interface follows the finger at `t`, as a share of BAR_PEEK or STRIP_NUDGE: 0 when nothing is
     *  to move. For the swipe down, 0 to 1: the top bar comes down that share of BAR_PEEK. For the bottom bar's drag,
     *  -1 to 1: its pictures move that share of STRIP_NUDGE the way the first stroke goes (to the left; negative: to the
     *  right). It follows the fingertip as it slides and goes back as the finger lets go. */
    fun nudge(hint: Hint, t: Double, still: Boolean = false): Double {
        if (still || !swipes(hint)) return 0.0
        val (_, u) = showing(hint, t) ?: return 0.0
        val k = floor(u / (STROKE + STROKE_GAP)).toInt()
        val v = u - k * (STROKE + STROKE_GAP)
        if (v >= STROKE) return 0.0
        val amount = progress(v) * (1 - smooth((v - MOVE_FROM - MOVE) / 0.13))   // back in place before the stroke ends
        return if (hint == Hint.STRIP_SCROLL && k % 2 == 1) -amount else amount
    }

    /** how far along its way the fingertip of a stroke is, `v` seconds into it */
    private fun progress(v: Double): Double = easeInOut((v - MOVE_FROM) / MOVE)

    /** what the tap does, inside the fingertip: two upright bars (pause) or a triangle (play), `s` times the size */
    private fun mark(d: Draw, cx: Double, cy: Double, r: Double, what: Hint?, alpha: Double) {
        val white = withAlpha(Colors.WHITE, 0.95 * alpha)
        when (what) {
            Hint.TAP_PAUSE -> {
                val bw = r * 0.26; val bh = r * 0.84; val gap = r * 0.2
                d.fillRoundRect(cx - gap - bw, cy - bh / 2, cx - gap, cy + bh / 2, bw * 0.3, white)
                d.fillRoundRect(cx + gap, cy - bh / 2, cx + gap + bw, cy + bh / 2, bw * 0.3, white)
            }
            Hint.TAP_PLAY -> {    // a little right of the middle, where it looks centred
                val s = r * 0.5
                d.beginPath(); d.moveTo(cx - s * 0.62, cy - s); d.lineTo(cx + s * 0.95, cy); d.lineTo(cx - s * 0.62, cy + s); d.closePath()
                d.fillPath(white)
            }
            else -> {}
        }
    }

    /** a tap at (cx, cy), `u` seconds into its showing: the fingertip (a dot of radius r) comes, presses down and lets
     *  go, three rings leave it one after the other and grow by `reach` as they fade, and it fades */
    private fun tap(d: Draw, cx: Double, cy: Double, u: Double, r: Double, reach: Double, dp: Double, what: Hint?) {
        val come = smooth(u / APPEAR)
        val a = min(come, 1 - smooth((u - (TAP_PLAYS - 0.25)) / 0.25))
        val down = smooth((u - APPEAR) / (PRESS * 0.6)) * (1 - smooth((u - APPEAR - PRESS) / 0.12))
        val scale = (1.15 - 0.15 * come) * (1 - 0.16 * down)
        for (k in 0 until RINGS) {
            val v = (u - APPEAR - k * RING_EVERY) / RING_LASTS
            if (v <= 0.0 || v >= 1.0) continue
            d.strokeArc(cx, cy, r + reach * easeOut(v), 0.0, 360.0, ink(0.5 * (1 - v)), 2.5 * dp)
        }
        val rr = r * scale
        d.fillRoundRect(cx - rr, cy - rr, cx + rr, cy + rr, rr, ink((0.48 + 0.14 * down) * a))
        mark(d, cx, cy, rr, what, a)
    }

    /** the same as a still picture, for a phone that asks for less motion: the fingertip and one ring, fading in and out */
    private fun tapStill(d: Draw, cx: Double, cy: Double, u: Double, r: Double, reach: Double, dp: Double, what: Hint?) {
        val a = min(smooth(u / 0.25), 1 - smooth((u - (TAP_PLAYS - 0.35)) / 0.35))
        d.strokeArc(cx, cy, r + reach * 0.55, 0.0, 360.0, ink(0.3 * a), 2.5 * dp)
        d.fillRoundRect(cx - r, cy - r, cx + r, cy + r, r, ink(0.5 * a))
        mark(d, cx, cy, r, what, a)
    }

    /** one stroke of a swipe from (x0, y0) to (x1, y1), `v` seconds into it: the fingertip (a dot of radius r) comes,
     *  presses, slides with a short tail behind it, lets go and fades */
    private fun stroke(d: Draw, x0: Double, y0: Double, x1: Double, y1: Double, v: Double, r: Double, dp: Double) {
        val come = smooth(v / 0.12)
        val a = min(come, 1 - smooth((v - (STROKE - 0.18)) / 0.18))
        val down = smooth((v - 0.12) / 0.06) * (1 - smooth((v - MOVE_FROM - MOVE) / 0.1))   // pressed while it touches
        val scale = (1.15 - 0.15 * come) * (1 - 0.16 * down)
        val f = progress(v)
        val x = x0 + (x1 - x0) * f; val y = y0 + (y1 - y0) * f
        // the tail: behind the dot over the last part of the way, running out to a point, and going as the finger stops.
        // It starts on the dot's back edge, as wide as the dot is there, so that the two see-through shapes do not lie
        // on each other (one shape, and so evenly see-through)
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val tail = min(f, 0.35) * (1 - smooth((v - MOVE_FROM - MOVE) / 0.12))
        val rr = r * scale
        if (tail * len > rr * 1.2) {
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len      // along the way
            val bx = x - ux * rr * 0.75; val by = y - uy * rr * 0.75   // on the dot, three quarters of the way to its back
            val half = rr * 0.66                                       // half the dot's width there
            d.beginPath(); d.moveTo(bx - uy * half, by + ux * half); d.lineTo(x - ux * len * tail, y - uy * len * tail); d.lineTo(bx + uy * half, by - ux * half); d.closePath()
            d.fillPath(ink(0.24 * a))
        }
        d.fillRoundRect(x - rr, y - rr, x + rr, y + rr, rr, ink((0.48 + 0.14 * down) * a))
    }

    /** a swipe as a still picture, for a phone that asks for less motion: the fingertip where it starts and an arrow
     *  along its way (pointing both ways for the bottom bar, which goes both ways), fading in and out */
    private fun swipeStill(d: Draw, x0: Double, y0: Double, x1: Double, y1: Double, u: Double, r: Double, dp: Double, both: Boolean) {
        val a = min(smooth(u / 0.25), 1 - smooth((u - (SWIPE_PLAYS - 0.35)) / 0.35))
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
        val head = 9 * dp
        fun arrowHead(x: Double, y: Double, dx: Double, dy: Double) {
            d.beginPath(); d.moveTo(x - dx * head - dy * head * 0.8, y - dy * head + dx * head * 0.8); d.lineTo(x, y)
            d.lineTo(x - dx * head + dy * head * 0.8, y - dy * head - dx * head * 0.8)
            d.strokePath(ink(0.45 * a), 3 * dp)
        }
        val sx = if (both) x0 - ux * (len * 0.15) else x0 + ux * (r + 4 * dp); val sy = if (both) y0 - uy * (len * 0.15) else y0 + uy * (r + 4 * dp)
        d.beginPath(); d.moveTo(sx, sy); d.lineTo(x1, y1)
        d.strokePath(ink(0.35 * a), 3 * dp)
        arrowHead(x1, y1, ux, uy)
        if (both) arrowHead(sx, sy, -ux, -uy)
        d.fillRoundRect(x0 - r, y0 - r, x0 + r, y0 + r, r, ink(0.5 * a))
    }
}
