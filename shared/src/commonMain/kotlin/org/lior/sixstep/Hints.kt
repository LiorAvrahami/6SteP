package org.lior.sixstep

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What the dance floor can do that nothing on the screen says (user 2026-11-21: "People that use this app ... it's not
 *  intuitive to them, the things that they can do ... what I want is the most minimalistic addition to the interface
 *  that will make it so that people will engage with it in the ways that they can. ... a series of flags ... that
 *  indicate what the user has already done. And once a user has already used some functionality, then we don't need to
 *  hint at it anymore. If the user didn't use some functionality, then we keep hinting ... until they use" it).
 *
 *  Each of these is one such thing, with a flag of its own (`key`: the name the flag is kept under, see
 *  FloorHost.knows) and a small moving picture that shows the gesture (HintPainter). The dance floor shows one hint at
 *  a time, the one that fits what is on the screen, and only while its flag is down (DanceFloor.hintNow). Doing the
 *  thing once raises the flag for good.
 *
 *  There are three things (the user, 2026-11-22: "we don't need more hints, just the three"): the tap (that pauses,
 *  that plays), the swipe down and the swipe to the side. From v71 to v77 there were two more, for the bottom bar (a
 *  tap on one of its steps, "strip_tap", and its drag, "strip_scroll"); the user, the same day: "there is still a
 *  hint on the bottom bar itself. remove it" and "there should be no bottom bar hints". Their flags may still be kept
 *  on a phone or in a browser; nothing reads them. */
enum class Hint(val key: String) {
    /** a tap on the floor pauses the dance */
    TAP_PAUSE("tap_pause"),
    /** a tap on the floor plays it */
    TAP_PLAY("tap_play"),
    /** a swipe down on the floor brings the top and the bottom bar */
    SWIPE_DOWN("bars"),
    /** a swipe to the left or the right on the floor: the next or the previous step while the dance stands, the next
     *  or the previous part while it plays (user 2026-11-22: "there needs to be slide side to side hint") */
    SWIPE_SIDE("swipe_side");

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
 *    (0.12 s), moves or lets go, and fades: one tap about 1 s, one swipe stroke 0.88 s with 0.495 s of movement (0.8
 *    and 0.45 s in v74; the user, 2026-11-22: "keep it one slide that is 10% slower").
 *  - The real interface makes a little of the move too, so that the hint shows what the gesture does: Material's
 *    gesture education plays "a partial version of the gesture" behind its touch indicator, and Microsoft's swipe hint
 *    moves the item itself a short way (15 px vertically, 23 px horizontally). So the top bar peeks in by BAR_PEEK dp
 *    while the finger swipes down (nudge: the screens move it), with a little red arrow on it (barArrow; the user,
 *    2026-11-22: "add a little red alpha=0.8 arrow on the top bar that indicates the movement, and in this animation
 *    make the protruding top bar protrude x2 deeper into the view and for x1.5 longer").
 *  - A hint never plays while the user is busy: it waits until the screen has been left alone (delay, counted from
 *    the last touch: DanceFloor.touched restarts it), and any touch makes it go at once. Two hints follow a move of
 *    the user's by a second instead: the swipe down after a pause (AFTER_PAUSE), the swipe to the side after the
 *    swipe that brought the bars (AFTER_BARS). None at all in the first FIRST seconds after a dance floor opens (the
 *    user, 2026-11-22: "make the first hint appear after 8 seconds of the start of the app"; 14 s from v75 to v78).
 *    DanceFloor works out when each first showing is and tells the screen (`first`). Material: education only for
 *    users who have not done the gesture yet, on the first run and in later sessions for those who still have not;
 *    the hints found in apps and libraries are triggered by idleness and cancelled by a touch.
 *  - After a showing the next comes GAP_MIN to GAP_MAX seconds after it ended, at random (the user, 2026-11-22:
 *    "People are still missing the hints ... make consecutive ones come at random time intervals after the previous
 *    hint completed where the time is distributed in 2sec to 5sec"), and so on until the gesture is used: a hint
 *    never gives up (the user's own wish on 2026-11-21, "never give up until the user does the gesture"; from v74 to
 *    v78 one showed three times, after the common practice found, and then rested until the next touch). `seed`
 *    makes the same clock give the same times, so that drawing, waiting and nudging agree; a screen takes a new one
 *    whenever the clock starts again.
 *  - Where the phone asks for less motion, a still picture fades in and out instead (still): NN/g and the design
 *    systems that were looked up ask to respect "reduce motion".
 *
 *  Their one sign is a fingertip: a round dot. A dot with three rings going out from it is a tap (the user's own idea:
 *  "3 consecutive circles that appear that indicate that you tap"); a mark inside it says what the tap does (pause,
 *  play). A dot that slides is a swipe, once per showing (twice in a row in v72 to v74, at the user's word; then, on
 *  2026-11-22: "the slide hints being two slides each is confusing. keep it one slide"); the swipe to the side goes to
 *  the left in one showing and to the right in the next, since it works both ways. Everything is drawn in red, OPACITY
 *  opaque, the marks inside the taps in white (the user, 2026-11-22: "People are still missing the hints, so I think
 *  make the hints maybe red with maybe alpha equals 0.8"; until v78 the floor's dark grey, half see-through). Lengths
 *  are in dp; `dp` is one of them in the canvas's own pixels. */
object HintPainter {
    /** seconds without a touch before a hint first shows: from when it became the floor's hint, or from the user's
     *  last touch, whichever came later */
    fun delay(hint: Hint): Double = when (hint) {
        Hint.TAP_PAUSE -> 4.0      // first the dance is watched
        Hint.TAP_PLAY -> 5.0       // whoever paused did so to look (user 2026-11-21: "wait a few seconds ... before you hint them to play")
        Hint.SWIPE_DOWN -> 3.0
        Hint.SWIPE_SIDE -> 3.0
    }
    /** s after a pause before the swipe-down hint (user 2026-11-22: "After I pause, then I want the hint, the slide down
     *  hint to come immediately after that"; and then: "like 1 second ... after"; 0.4 s in v76) */
    const val AFTER_PAUSE = 1.0
    /** s after the swipe that brought the bars before the hint to swipe to the side on the floor (user 2026-11-22: "after
     *  I slide down, then there should be a hint to slide side to side on the dance floor itself. Again, pretty much
     *  immediately, like with a lag of 1 second") */
    const val AFTER_BARS = 1.0
    /** s from the end of one showing to the start of the next: at random between these (the user, 2026-11-22: "random
     *  time intervals after the previous hint completed where the time is distributed in 2sec to 5sec"). Until v78:
     *  three showings 9.2 s apart (4.6 s for the swipes), then nothing until a touch. */
    const val GAP_MIN = 2.0
    const val GAP_MAX = 5.0
    /** s after a dance floor opens before any hint shows (the user, 2026-11-22: "make the first hint appear after 8
     *  seconds of the start of the app"; 14 s from v75 to v78, "wait 14 seconds from starting to showing the first
     *  hint"); DanceFloor keeps to it when it tells the screen when a hint first shows (firstAt) */
    const val FIRST = 8.0
    /** how opaque a hint is drawn (the user, 2026-11-22: "red with maybe alpha equals 0.8") */
    const val OPACITY = 0.8

    // a tap: the fingertip comes, presses and lets go; three rings go out from where it pressed; it fades
    const val APPEAR = 0.18        // s: the fingertip comes (fading in, settling from a little larger)
    const val PRESS = 0.12         // s: it presses down (a little smaller and darker), then lets go
    const val RINGS = 3
    const val RING_EVERY = 0.15    // s between two rings
    const val RING_LASTS = 0.5     // s: a ring from the fingertip's edge to gone, slowing as it goes
    const val TAP_PLAYS = 1.05     // s: the whole tap, its fading included

    // a swipe: one stroke, in which the fingertip comes, presses, slides, lets go and fades. Its times are v74's made
    // 10% longer (user 2026-11-22: "keep it one slide that is 10% slower")
    const val STROKE = 0.88        // s: the stroke
    const val MOVE_FROM = 0.22     // s into it: the slide begins
    const val MOVE = 0.495         // s: the slide, slow at both ends
    const val SWIPE_PLAYS = STROKE

    // the top bar during the swipe down (nudge): it comes in with the finger, stays out, and goes back in
    const val BAR_PEEK = 36.0      // dp it comes in: twice v78's 18 (the user, 2026-11-22: "x2 deeper into the view")
    const val PEEK_BACK = 0.143    // s: it goes back in
    /** s it is out in all: 1.5 times v78's, when it went back as soon as the finger stopped (the user, 2026-11-22: "for
     *  x1.5 longer") */
    const val PEEK_LASTS = 1.5 * (MOVE + PEEK_BACK)
    /** s: a showing of the swipe down, which lasts until the top bar is back in */
    const val DOWN_PLAYS = MOVE_FROM + PEEK_LASTS

    private fun ink(alpha: Double): Int = withAlpha(Colors.HINT, alpha)
    private fun smooth(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }
    private fun easeOut(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return 1 - (1 - u) * (1 - u) * (1 - u) }
    private fun easeInOut(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return if (u < 0.5) 4 * u * u * u else 1 - (2 - 2 * u).let { it * it * it } / 2 }

    /** where a hint's picture is, so that a screen can also tell without drawing: the centre of a tap, or the two ends
     *  of a swipe. `w`, `h`: the floor's whole size; `bottomBar`: how much of it the bottom bar covers now. */
    fun place(hint: Hint, w: Double, h: Double, dp: Double, bottomBar: Double): DoubleArray = when (hint) {
        // where a right thumb rests on a phone held sideways: beside the dancers, not on them
        Hint.TAP_PAUSE, Hint.TAP_PLAY -> doubleArrayOf(w - 104 * dp, h - bottomBar - 88 * dp)
        // down from the top edge, under the middle of the top bar's empty stretch: its buttons end 440 dp from the left
        // (16 + play 48 + 10 + tempo 58 + 10 + song 240 + 10 + mute 48, in the app and on the web page alike) and the
        // back button starts 64 dp from the right; so the arrow on the bar (barArrow) sits on no button
        Hint.SWIPE_DOWN -> { val x = (376 * dp + w) / 2; doubleArrayOf(x, 26 * dp, x, 116 * dp) }
        // across the floor, low, under the dancers and just above the bottom bar (and its annotations) when that is shown
        Hint.SWIPE_SIDE -> doubleArrayOf(w * 0.62, h - bottomBar - 36 * dp, w * 0.38, h - bottomBar - 36 * dp)
    }

    /** how long one showing of the hint takes, in seconds */
    fun plays(hint: Hint): Double = when (hint) {
        Hint.SWIPE_DOWN -> DOWN_PLAYS
        Hint.SWIPE_SIDE -> SWIPE_PLAYS
        Hint.TAP_PAUSE, Hint.TAP_PLAY -> TAP_PLAYS
    }

    /** seconds from the start of the hint's clock to its first showing: `wait` (its own delay, or a second after a move
     *  of the user's: AFTER_PAUSE, AFTER_BARS), or longer while the first FIRST seconds of the floor last (`hold`: how
     *  much of them is left at the clock's start). DanceFloor decides (`first`). */
    fun firstAt(hint: Hint, hold: Double = 0.0, wait: Double = delay(hint)): Double = max(wait, hold)

    /** where `t` (seconds from the clock's start) falls: in showing k, u seconds into it (u >= 0), or before showing k,
     *  -u seconds ahead of it (u < 0). The first showing is at `first`; each next one a random GAP_MIN to GAP_MAX
     *  seconds after the one before ended, the same for the same `seed`. */
    private fun schedule(hint: Hint, t: Double, first: Double, seed: Int): Pair<Int, Double> {
        val lasts = plays(hint)
        val random = Random(seed)
        var start = first
        var k = 0
        while (t >= start + lasts && k < 1_000_000) {
            start += lasts + GAP_MIN + (GAP_MAX - GAP_MIN) * random.nextDouble()
            k++
        }
        return Pair(k, t - start)
    }

    /** when showing `k` (0: the first) starts, in seconds from the clock's start */
    fun start(hint: Hint, k: Int, first: Double = delay(hint), seed: Int = 0): Double {
        val lasts = plays(hint)
        val random = Random(seed)
        var start = first
        repeat(k) { start += lasts + GAP_MIN + (GAP_MAX - GAP_MIN) * random.nextDouble() }
        return start
    }

    /** which showing `t` seconds fall in, and how far into it they are; null between showings */
    private fun showing(hint: Hint, t: Double, first: Double, seed: Int): Pair<Int, Double>? {
        val (k, u) = schedule(hint, t, first, seed)
        return if (u >= 0) Pair(k, u) else null
    }

    /** how many seconds from `t` (counted as in draw) until the hint's picture next plays: 0 while it plays. A screen
     *  need not draw anything until then. */
    fun wait(hint: Hint, t: Double, first: Double = delay(hint), seed: Int = 0): Double {
        val (_, u) = schedule(hint, t, first, seed)
        return if (u >= 0) 0.0 else -u
    }

    /** Draw `hint` as it looks `t` seconds after it became the floor's hint (or after the user's last touch). True
     *  while it plays (false: nothing was drawn, it is waiting or resting). `still`: the phone asks for less motion;
     *  `first`: seconds from the clock's start to the first showing (DanceFloor decides; see firstAt); `seed`: the
     *  clock's own random times between showings. */
    fun draw(d: Draw, hint: Hint, t: Double, w: Double, h: Double, dp: Double, bottomBar: Double, still: Boolean = false, first: Double = delay(hint), seed: Int = 0): Boolean {
        val (n, u) = showing(hint, t, first, seed) ?: return false
        val at = place(hint, w, h, dp, bottomBar)
        when (hint) {
            Hint.TAP_PAUSE, Hint.TAP_PLAY -> if (still) tapStill(d, at[0], at[1], u, 18 * dp, 30 * dp, dp, hint) else tap(d, at[0], at[1], u, 18 * dp, 30 * dp, dp, hint)
            Hint.SWIPE_DOWN, Hint.SWIPE_SIDE -> {
                val both = hint == Hint.SWIPE_SIDE   // sideways: either way
                val r = 12 * dp
                if (still) swipeStill(d, at[0], at[1], at[2], at[3], u, r, dp, both, plays(hint))
                else {
                    // one stroke; the sideways one goes to the left in one showing and to the right in the next
                    if (u < STROKE) {
                        if (both && n % 2 == 1) stroke(d, at[2], at[3], at[0], at[1], u, r, dp)
                        else stroke(d, at[0], at[1], at[2], at[3], u, r, dp)
                    }
                    // the little arrow on the top bar, which comes in with the swipe down
                    if (hint == Hint.SWIPE_DOWN) barArrow(d, w, at[0], peekAt(u) * BAR_PEEK * dp, dp)
                }
            }
        }
        return true
    }

    /** How far the real interface follows the finger at `t`, as a share of BAR_PEEK: 0 when nothing is to move. For the
     *  swipe down, 0 to 1: the top bar comes down that share of BAR_PEEK; it follows the fingertip as it slides, stays
     *  out, and goes back in (peekAt). Nothing else moves: the swipe to the side does a whole step of the dance, not a
     *  little of anything. */
    fun nudge(hint: Hint, t: Double, still: Boolean = false, first: Double = delay(hint), seed: Int = 0): Double {
        if (still || hint != Hint.SWIPE_DOWN) return 0.0
        val (_, u) = showing(hint, t, first, seed) ?: return 0.0
        return peekAt(u)
    }

    /** how far out the top bar is, 0 to 1, `u` seconds into a showing of the swipe down: it comes with the finger, stays
     *  out, and goes back in over PEEK_BACK, out for PEEK_LASTS in all */
    private fun peekAt(u: Double): Double = progress(u) * (1 - smooth((u - (MOVE_FROM + PEEK_LASTS - PEEK_BACK)) / PEEK_BACK))

    /** the little arrow on the top bar as it comes in with the swipe down (the user, 2026-11-22: "add a little red
     *  alpha=0.8 arrow on the top bar that indicates the movement"): pointing down, above the finger's way (`cx`, on none
     *  of the bar's buttons), its
     *  tip a little above the bar's lower edge (`edge` px from the top of the screen). It is on the bar, so it comes in
     *  and goes back with it; what is not in yet is cut off. */
    private fun barArrow(d: Draw, w: Double, cx: Double, edge: Double, dp: Double) {
        if (edge < 0.5) return
        val tip = edge - 6 * dp; val tail = tip - 22 * dp; val neck = tip - 10 * dp
        val half = 8 * dp; val shaft = 2.5 * dp
        d.save(); d.clipRect(0.0, 0.0, w, edge)
        d.beginPath()
        d.moveTo(cx - shaft, tail); d.lineTo(cx + shaft, tail); d.lineTo(cx + shaft, neck); d.lineTo(cx + half, neck)
        d.lineTo(cx, tip); d.lineTo(cx - half, neck); d.lineTo(cx - shaft, neck); d.closePath()
        d.fillPath(ink(OPACITY))
        d.restore()
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
            d.strokeArc(cx, cy, r + reach * easeOut(v), 0.0, 360.0, ink(OPACITY * (1 - v)), 2.5 * dp)
        }
        val rr = r * scale
        d.fillRoundRect(cx - rr, cy - rr, cx + rr, cy + rr, rr, ink(OPACITY * a))
        mark(d, cx, cy, rr, what, a)
    }

    /** the same as a still picture, for a phone that asks for less motion: the fingertip and one ring, fading in and out */
    private fun tapStill(d: Draw, cx: Double, cy: Double, u: Double, r: Double, reach: Double, dp: Double, what: Hint?) {
        val a = min(smooth(u / 0.25), 1 - smooth((u - (TAP_PLAYS - 0.35)) / 0.35))
        d.strokeArc(cx, cy, r + reach * 0.55, 0.0, 360.0, ink(OPACITY * 0.6 * a), 2.5 * dp)
        d.fillRoundRect(cx - r, cy - r, cx + r, cy + r, r, ink(OPACITY * a))
        mark(d, cx, cy, r, what, a)
    }

    /** one stroke of a swipe from (x0, y0) to (x1, y1), `v` seconds into it: the fingertip (a dot of radius r) comes,
     *  presses, slides with a short tail behind it, lets go and fades */
    private fun stroke(d: Draw, x0: Double, y0: Double, x1: Double, y1: Double, v: Double, r: Double, dp: Double) {
        val come = smooth(v / 0.132)
        val a = min(come, 1 - smooth((v - (STROKE - 0.198)) / 0.198))
        val down = smooth((v - 0.132) / 0.066) * (1 - smooth((v - MOVE_FROM - MOVE) / 0.11))   // pressed while it touches
        val scale = (1.15 - 0.15 * come) * (1 - 0.16 * down)
        val f = progress(v)
        val x = x0 + (x1 - x0) * f; val y = y0 + (y1 - y0) * f
        // the tail: behind the dot over the last part of the way, running out to a point, and going as the finger stops.
        // It starts on the dot's back edge, as wide as the dot is there, so that the two see-through shapes do not lie
        // on each other (one shape, and so evenly see-through)
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val tail = min(f, 0.35) * (1 - smooth((v - MOVE_FROM - MOVE) / 0.132))
        val rr = r * scale
        if (tail * len > rr * 1.2) {
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len      // along the way
            val bx = x - ux * rr * 0.75; val by = y - uy * rr * 0.75   // on the dot, three quarters of the way to its back
            val half = rr * 0.66                                       // half the dot's width there
            d.beginPath(); d.moveTo(bx - uy * half, by + ux * half); d.lineTo(x - ux * len * tail, y - uy * len * tail); d.lineTo(bx + uy * half, by - ux * half); d.closePath()
            d.fillPath(ink(OPACITY * 0.5 * a))   // the tail lighter than the fingertip, so that it reads as a trail
        }
        d.fillRoundRect(x - rr, y - rr, x + rr, y + rr, rr, ink(OPACITY * a))
    }

    /** a swipe as a still picture, for a phone that asks for less motion: the fingertip where it starts and an arrow
     *  along its way (pointing both ways for the swipe to the side, which goes both ways), fading in and out */
    private fun swipeStill(d: Draw, x0: Double, y0: Double, x1: Double, y1: Double, u: Double, r: Double, dp: Double, both: Boolean, lasts: Double) {
        val a = min(smooth(u / 0.25), 1 - smooth((u - (lasts - 0.35)) / 0.35))
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
        val head = 9 * dp
        fun arrowHead(x: Double, y: Double, dx: Double, dy: Double) {
            d.beginPath(); d.moveTo(x - dx * head - dy * head * 0.8, y - dy * head + dx * head * 0.8); d.lineTo(x, y)
            d.lineTo(x - dx * head + dy * head * 0.8, y - dy * head - dx * head * 0.8)
            d.strokePath(ink(OPACITY * a), 3 * dp)
        }
        val sx = if (both) x0 - ux * (len * 0.15) else x0 + ux * (r + 4 * dp); val sy = if (both) y0 - uy * (len * 0.15) else y0 + uy * (r + 4 * dp)
        d.beginPath(); d.moveTo(sx, sy); d.lineTo(x1, y1)
        d.strokePath(ink(OPACITY * 0.7 * a), 3 * dp)
        arrowHead(x1, y1, ux, uy)
        if (both) arrowHead(sx, sy, -ux, -uy)
        d.fillRoundRect(x0 - r, y0 - r, x0 + r, y0 + r, r, ink(OPACITY * a))
    }
}
