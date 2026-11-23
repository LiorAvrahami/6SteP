package org.lior.sixstep

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The hints' pictures (HintPainter), drawn onto a canvas that only writes down what it is asked to draw: when each
 *  shows and rests, that it stays where it belongs, and that a screen is told rightly how long it may sleep. (How they
 *  look was judged by eye, on the web page.) */
class HintsTest {
    /** what was drawn: how many shapes, and the box around them (as cut by clipRect, as a real canvas cuts it) */
    private class Sheet : Draw {
        var shapes = 0
        var l = Double.POSITIVE_INFINITY; var t = Double.POSITIVE_INFINITY; var r = Double.NEGATIVE_INFINITY; var b = Double.NEGATIVE_INFINITY
        val colors = HashSet<Int>()
        /** each shape drawn, with its colour (without its alpha) and its own box: l, t, r, b */
        val boxes = ArrayList<Pair<Int, DoubleArray>>()
        private val cur = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        private val path = ArrayList<Pair<Double, Double>>()
        private var clip: DoubleArray? = null
        private val saved = ArrayList<DoubleArray?>()
        private fun mark(x0: Double, y0: Double) {
            var x = x0; var y = y0
            clip?.let { x = x.coerceIn(it[0], it[2]); y = y.coerceIn(it[1], it[3]) }
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            cur[0] = min(cur[0], x); cur[1] = min(cur[1], y); cur[2] = max(cur[2], x); cur[3] = max(cur[3], y)
        }
        private fun shape(color: Int) {
            if (Colors.alpha(color) > 0) { shapes++; colors.add(color and 0x00FFFFFF); boxes.add(Pair(color and 0x00FFFFFF, cur.copyOf())) }
            cur[0] = Double.POSITIVE_INFINITY; cur[1] = Double.POSITIVE_INFINITY; cur[2] = Double.NEGATIVE_INFINITY; cur[3] = Double.NEGATIVE_INFINITY
        }
        override fun fillAll(color: Int) { shape(color) }
        override fun line(x0: Double, y0: Double, x1: Double, y1: Double, color: Int, width: Double) { mark(x0, y0); mark(x1, y1); shape(color) }
        override fun fillRect(l: Double, t: Double, r: Double, b: Double, color: Int) { mark(l, t); mark(r, b); shape(color) }
        override fun strokeRect(l: Double, t: Double, r: Double, b: Double, color: Int, width: Double) { mark(l, t); mark(r, b); shape(color) }
        override fun fillRoundRect(l: Double, t: Double, r: Double, b: Double, radius: Double, color: Int) { if (Colors.alpha(color) > 0) { mark(l, t); mark(r, b) }; shape(color) }
        override fun beginPath() { path.clear() }
        override fun moveTo(x: Double, y: Double) { path.add(Pair(x, y)) }
        override fun lineTo(x: Double, y: Double) { path.add(Pair(x, y)) }
        override fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) { path.add(Pair(x3, y3)) }
        override fun closePath() {}
        override fun fillPath(color: Int) { if (Colors.alpha(color) > 0) for (p in path) mark(p.first, p.second); shape(color) }
        override fun strokePath(color: Int, width: Double, dash: DoubleArray?) { if (Colors.alpha(color) > 0) for (p in path) mark(p.first, p.second); shape(color) }
        override fun strokeArc(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double, color: Int, width: Double) {
            if (Colors.alpha(color) > 0) { mark(cx - r, cy - r); mark(cx + r, cy + r) }; shape(color)
        }
        override fun save() { saved.add(clip) }
        override fun clipRect(l: Double, t: Double, r: Double, b: Double) {
            val c = clip
            clip = if (c == null) doubleArrayOf(l, t, r, b) else doubleArrayOf(max(l, c[0]), max(t, c[1]), min(r, c[2]), min(b, c[3]))
        }
        override fun restore() { clip = saved.removeAt(saved.size - 1) }
        override fun text(s: String, x: Double, y: Double, size: Double, color: Int, weight: FontWeight, align: TextAlign) { shape(color) }
        override fun textWidth(s: String, size: Double, weight: FontWeight): Double = s.length * size * 0.6
    }

    // a phone held sideways: 831 x 387 dp, 2.625 pixels to the dp
    private val dp = 2.625; private val w = 831 * dp; private val h = 387 * dp; private val strip = 132 * dp
    private val red = Colors.HINT and 0x00FFFFFF; private val white = Colors.WHITE and 0x00FFFFFF

    private fun at(hint: Hint, t: Double, bottomBar: Double, first: Double = HintPainter.delay(hint), seed: Int = 0): Pair<Boolean, Sheet> {
        val s = Sheet()
        return Pair(HintPainter.draw(s, hint, t, w, h, dp, bottomBar, first = first, seed = seed), s)
    }

    /** the common practice looked up on 2026-11-22 (HintPainter's comment): movements of 200 to 500 ms, eased; a hint
     *  that waits for the user to be idle; still pictures where less motion is asked for. And the user's own: after
     *  each showing the next comes 2 to 5 s later, at random ("make consecutive ones come at random time intervals after
     *  the previous hint completed where the time is distributed in 2sec to 5sec"), for as long as the gesture has not
     *  been used; red, 0.8 opaque ("make the hints maybe red with maybe alpha equals 0.8") */
    @Test fun everyHintWaitsAndComesBackAtRandom() {
        for (hint in Hint.values()) for (seed in listOf(0, 1, 7, -42, 123456)) {
            val bar = 0.0   // the bars away
            val delay = HintPainter.delay(hint)
            assertTrue(delay >= 2.0, "$hint waits for the user to leave the screen alone: $delay s")
            // before its time: nothing, and the screen is told how long that is
            val (early, nothing) = at(hint, delay - 0.5, bar, seed = seed)
            assertFalse(early); assertEquals(0, nothing.shapes, "$hint draws nothing before its time")
            assertEquals(0.5, HintPainter.wait(hint, delay - 0.5, seed = seed), 1e-9)
            // then it plays, rests and plays again: something is drawn, in red and white only, inside the screen
            var t = delay; var wasPlaying = false
            val starts = ArrayList<Double>(); val ends = ArrayList<Double>()
            while (t < delay + 60.0) {
                val (playing, s) = at(hint, t, bar, seed = seed)
                if (playing && !wasPlaying) starts.add(t)
                if (!playing && wasPlaying) ends.add(t)
                wasPlaying = playing
                if (playing) {
                    assertEquals(0.0, HintPainter.wait(hint, t, seed = seed), 1e-9, "$hint: no sleeping while it plays")
                    if (s.shapes > 0) {
                        assertTrue(s.l >= 0 && s.t >= 0 && s.r <= w && s.b <= h, "$hint at $t s stays on the screen: ${s.l}, ${s.t}, ${s.r}, ${s.b}")
                        assertTrue(s.colors.all { it == red || it == white }, "$hint is drawn in red and white")
                    }
                } else {
                    assertEquals(0, s.shapes, "$hint draws nothing while it rests")
                    val sleep = HintPainter.wait(hint, t, seed = seed)
                    assertTrue(sleep > 0 && sleep.isFinite(), "$hint at $t s: the screen may sleep, and is woken again ($sleep s)")
                    assertTrue(at(hint, t + sleep + 0.02, bar, seed = seed).first, "$hint plays again when the screen wakes (${t + sleep} s)")
                    assertFalse(sleep > 0.03 && at(hint, t + sleep - 0.02, bar, seed = seed).first, "$hint: and not before")
                }
                t += 0.01
            }
            // each showing as long as plays(hint): the hand's two seconds of hovering, and a second or so of the gesture;
            // between them 2 to 5 s, at random; and on and on
            assertTrue(HintPainter.plays(hint) in 3.0..3.6, "$hint takes ${HintPainter.plays(hint)} s")
            for (i in ends.indices) assertEquals(HintPainter.plays(hint), ends[i] - starts[i], 0.02, "$hint: one showing")
            val gaps = (1 until starts.size).map { starts[it] - ends[it - 1] }
            assertTrue(gaps.size >= 8, "$hint keeps coming: ${starts.size} showings in a minute")
            assertTrue(gaps.all { it > 1.98 && it < 5.02 }, "$hint: 2 to 5 s between showings: $gaps")
            assertTrue(gaps.maxOrNull()!! - gaps.minOrNull()!! > 0.5, "$hint: at random, not evenly: $gaps")
            for (k in 0..5) assertEquals(starts[k], HintPainter.start(hint, k, delay, seed), 0.011, "$hint: showing $k where start() says")
            // it never gives up: still coming after ten minutes without a touch
            assertTrue((0 until 900).any { at(hint, 600.0 + it * 0.01, bar, seed = seed).first }, "$hint: still there after ten minutes")
            // the same in still pictures: something is drawn while it plays, and nothing moves
            val a = Sheet(); val b = Sheet()
            assertTrue(HintPainter.draw(a, hint, delay + 0.4, w, h, dp, bar, still = true, seed = seed))
            assertTrue(HintPainter.draw(b, hint, delay + HintPainter.plays(hint) - 0.45, w, h, dp, bar, still = true, seed = seed))
            assertTrue(a.shapes > 0 && b.shapes > 0, "$hint: a still picture")
            assertEquals(a.l, b.l, 1e-9); assertEquals(a.t, b.t, 1e-9); assertEquals(a.r, b.r, 1e-9); assertEquals(a.b, b.b, 1e-9)
            assertEquals(0.0, HintPainter.nudge(hint, delay + 0.5, still = true, seed = seed), "$hint moves nothing in still pictures")
            // nothing in the first FIRST seconds after the floor opened (user 2026-11-22: "make the first hint appear
            // after 8 seconds"): with the whole of them still to come, the first showing is at FIRST
            val first = HintPainter.firstAt(hint, HintPainter.FIRST)
            assertEquals(8.0, first)
            assertEquals(8.0, HintPainter.wait(hint, 0.0, first, seed), 1e-9)
            assertFalse(at(hint, 7.95, bar, first, seed).first, "$hint: not before 8 s")
            assertTrue(at(hint, 8.05, bar, first, seed).first, "$hint: at 8 s")
            assertEquals(delay, HintPainter.firstAt(hint, 1.0), "$hint: with less of them left than its own wait, its own wait")
        }
        // another clock (a touch, another hint) takes other random times
        assertNotEquals(HintPainter.start(Hint.TAP_PAUSE, 1, seed = 1), HintPainter.start(Hint.TAP_PAUSE, 1, seed = 2))
        assertEquals(2.0, HintPainter.GAP_MIN, 1e-9); assertEquals(5.0, HintPainter.GAP_MAX, 1e-9)
        assertEquals(0.8, HintPainter.OPACITY, 1e-9)
        // the two that follow a move of the user's by a second wait for the floor's first seconds too (user 2026-11-22:
        // "yes, they should also wait ... after the dance opens")
        assertEquals(8.0, HintPainter.firstAt(Hint.SWIPE_DOWN, HintPainter.FIRST, HintPainter.AFTER_PAUSE), 1e-9)
        assertEquals(1.0, HintPainter.firstAt(Hint.SWIPE_SIDE, 0.0, HintPainter.AFTER_BARS), 1e-9)
        // every movement within 200 to 500 ms (NN/g, web.dev); the ripple a second, the hover two (the user, 2026-11-23)
        assertTrue(HintPainter.MOVE in 0.2..0.5 && HintPainter.PRESS in 0.08..0.2 && HintPainter.LIFT in 0.08..0.3, "the slide ${HintPainter.MOVE} s, the press ${HintPainter.PRESS} s")
        assertTrue(HintPainter.APPEAR in 0.1..0.3 && HintPainter.GO in 0.1..0.3)
        assertEquals(1.0, (HintPainter.RINGS - 1) * HintPainter.RING_EVERY + HintPainter.RING_LASTS, 1e-9, "the ripple: a second")
        assertEquals(2.0, HintPainter.HOVER, 1e-9)
        // the user waits a few seconds after a pause before the hint to play
        assertTrue(HintPainter.delay(Hint.TAP_PLAY) >= 4.0)
    }

    /** the real interface makes a little of the move: the top bar comes in with the swipe down's finger, stays out a
     *  while and goes back (user 2026-11-22: "make the protruding top bar protrude x2 deeper into the view and for x1.5
     *  longer") */
    @Test fun theTopBarComesInWithTheSwipeDown() {
        val hint = Hint.SWIPE_DOWN
        val t0 = HintPainter.delay(hint)
        var most = 0.0; var least = 0.0; var out = 0.0
        var t = t0
        while (t < t0 + HintPainter.plays(hint) + 0.3) { val n = HintPainter.nudge(hint, t); most = max(most, n); least = min(least, n); if (n > 0.001) out += 0.001; t += 0.001 }
        assertEquals(1.0, most, 0.01, "all the way, as the finger gets there")
        assertEquals(0.0, least, 1e-9, "the top bar only comes down")
        // v78's top bar, measured the same way: it came with the finger and went back as soon as the finger stopped
        fun smooth(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }
        fun easeInOut(u0: Double): Double { val u = u0.coerceIn(0.0, 1.0); return if (u < 0.5) 4 * u * u * u else 1 - (2 - 2 * u).let { it * it * it } / 2 }
        fun v78(u: Double) = easeInOut((u - 0.22) / 0.495) * (1 - smooth((u - 0.22 - 0.495) / 0.143))
        var before = 0.0; var u = 0.0
        while (u < 1.5) { if (v78(u) > 0.001) before += 0.001; u += 0.001 }
        assertEquals(1.5, out / before, 0.05, "out 1.5 times as long as in v78: $out s, then $before s")
        assertEquals(1.5 * (0.495 + 0.143), HintPainter.PEEK_LASTS, 1e-9)
        assertEquals(0.0, HintPainter.nudge(hint, t0 + HintPainter.MOVE_FROM - 0.01), 1e-9, "in while the hand hovers")
        assertEquals(1.0, HintPainter.nudge(hint, t0 + HintPainter.MOVE_FROM + HintPainter.MOVE + HintPainter.LIFT + 0.05), 1e-3, "still all the way out as the finger lifts and the hand goes")
        assertEquals(0.0, HintPainter.nudge(hint, t0 + HintPainter.plays(hint) - 0.001), 1e-3, "back in place as the showing ends")
        assertEquals(36.0, HintPainter.BAR_PEEK, 1e-9, "twice v78's 18 dp")
        for (other in listOf(Hint.TAP_PAUSE, Hint.TAP_PLAY, Hint.SWIPE_SIDE)) {
            for (k in 0..40) assertEquals(0.0, HintPainter.nudge(other, HintPainter.delay(other) + k * 0.025), "$other moves nothing")
        }
    }

    /** the user, 2026-11-23: "the hand hovers for 2 seconds and then presses down, and then there's the ripple effect
     *  ... and the ripple effect goes for like 1 second"; and for the swipes: "it just hovers there ..., and then as it
     *  starts to do the gesture, there's like a circle or like 2 consecutive circles around the fingertip that indicate
     *  that it's pressing down ... the hand moves down and the circle goes around along with it, and ... there's the
     *  trail ..., and then the finger pulls up and the circle disappears, and then the hand disappears" */
    @Test fun theHandHoversPressesAndGoes() {
        fun box(s: Sheet) = doubleArrayOf(s.l, s.t, s.r, s.b)
        // a tap: the hand alone (its white and its lines) for its first two seconds and more, the same all along
        val t0 = HintPainter.delay(Hint.TAP_PAUSE)
        val early = at(Hint.TAP_PAUSE, t0 + 0.5, 0.0).second; val late = at(Hint.TAP_PAUSE, t0 + HintPainter.PRESS_AT - 0.01, 0.0).second
        assertEquals(2, early.shapes, "the hand alone"); assertEquals(2, late.shapes, "the hand alone, still")
        for (i in 0..3) assertEquals(box(early)[i], box(late)[i], 1e-9, "it hovers: it does not move")
        assertTrue(HintPainter.PRESS_AT >= 2.0, "two seconds of hovering, once it has come")
        // then it presses (a little smaller) and the rings go out from the fingertip, for a second
        val pressedAt = HintPainter.PRESS_AT + HintPainter.PRESS
        val pressed = at(Hint.TAP_PAUSE, t0 + pressedAt + 0.05, 0.0).second
        assertTrue(pressed.boxes.any { it.first == white && it.second[2] - it.second[0] < (early.r - early.l) * 0.95 }, "pressed, the hand is smaller")
        fun rings(u: Double) = at(Hint.TAP_PAUSE, t0 + u, 0.0).second.boxes.count { it.first == red } - 1   // (the hand's lines are red too)
        assertEquals(0, rings(pressedAt - 0.02), "no ring before the press")
        assertTrue(rings(pressedAt + 0.1) >= 1 && rings(pressedAt + 0.5) >= 2, "rings once it has pressed")
        assertTrue(rings(pressedAt + 0.97) >= 1, "for a second")
        assertFalse(at(Hint.TAP_PAUSE, t0 + pressedAt + 1.02, 0.0).first, "and then the hand is gone with them")
        // a swipe: the hand alone, hovering where it starts
        val s0 = HintPainter.delay(Hint.SWIPE_SIDE)
        val p = HintPainter.place(Hint.SWIPE_SIDE, w, h, dp, 0.0)
        val hover = at(Hint.SWIPE_SIDE, s0 + 1.0, 0.0).second
        assertEquals(2, hover.shapes, "the hand alone")
        assertTrue(hover.l < p[0] && hover.r > p[0] && hover.t < p[1] && hover.b > p[1], "where the swipe starts")
        // it presses: the inner circle round the fingertip, and a moment later the outer
        fun circles(u: Double) = at(Hint.SWIPE_SIDE, s0 + u, 0.0).second.boxes.filter { it.first == red && it.second[2] - it.second[0] < 40 * dp && it.second[3] - it.second[1] < 40 * dp }
        assertEquals(0, circles(HintPainter.PRESS_AT - 0.01).size, "no circle while it hovers")
        assertEquals(1, circles(HintPainter.PRESS_AT + 0.05).size, "the first circle as it presses")
        assertEquals(2, circles(HintPainter.PRESS_AT + HintPainter.PRESS + 0.05).size, "then the second")
        // it slides, the circles with the fingertip
        val mid = HintPainter.MOVE_FROM + HintPainter.MOVE / 2
        val cs = circles(mid)
        assertEquals(2, cs.size)
        for (c in cs) {
            val cx = (c.second[0] + c.second[2]) / 2; val cy = (c.second[1] + c.second[3]) / 2
            assertEquals((p[0] + p[2]) / 2, cx, 0.5 * dp, "round the fingertip, half way along"); assertEquals(p[1], cy, 0.5 * dp)
        }
        // the finger lifts: the circles go, then the hand
        val lifted = HintPainter.MOVE_FROM + HintPainter.MOVE + HintPainter.LIFT + 0.02
        assertEquals(0, circles(lifted).size, "no circle once the finger has lifted")
        assertEquals(2, at(Hint.SWIPE_SIDE, s0 + lifted, 0.0).second.shapes, "the hand alone, going")
        assertFalse(at(Hint.SWIPE_SIDE, s0 + HintPainter.STROKE + 0.01, 0.0).first, "and gone")
        // the hand is Lucide's "pointer", as kept in design/lucide/pointer.svg
        val svg = java.io.File("../design/lucide/pointer.svg").readText()
        val paths = Regex("<path d=\"([^\"]+)\"").findAll(svg).map { it.groupValues[1] }.toList()
        assertEquals(paths, HintPainter.HAND_LINES, "the hand's lines are the icon's")
    }

    @Test fun theHintsAreWhereTheirGestureBelongs() {
        // the taps that pause and play: beside the dancers, low on the right, and above the bottom bar when that is shown
        val rippling = HintPainter.PRESS_AT + HintPainter.PRESS + 0.3
        for (hint in listOf(Hint.TAP_PAUSE, Hint.TAP_PLAY)) {
            val (_, s) = at(hint, HintPainter.delay(hint) + rippling, 0.0)
            assertTrue(s.shapes >= 4, "$hint: the hand (its white and its lines) and rings (${s.shapes} shapes)")
            assertTrue(s.l > w * 0.75 && s.t > h * 0.5, "$hint is low on the right: from (${s.l / dp}, ${s.t / dp}) dp")
            val (_, raised) = at(hint, HintPainter.delay(hint) + rippling, strip)
            assertTrue(raised.b < h - strip, "$hint keeps above the bottom bar: its lowest point ${raised.b / dp} dp of ${h / dp}")
            assertEquals(s.b - strip, raised.b, 1e-6, "by the bar's height exactly")
        }
        // the swipe down: from near the top edge, under the top bar's empty stretch (its buttons end 440 dp from the
        // left, the back button starts 64 dp from the right), going down
        val t0 = HintPainter.delay(Hint.SWIPE_DOWN)
        val a = at(Hint.SWIPE_DOWN, t0 + 1.0, 0.0).second   // the hand hovering where it starts
        val b = at(Hint.SWIPE_DOWN, t0 + HintPainter.MOVE_FROM + HintPainter.MOVE, 0.0).second
        assertTrue(a.l > 440 * dp && a.r < w - 64 * dp && a.t < 26 * dp, "the swipe down starts at the top, under the bar's empty stretch: from (${a.l / dp}, ${a.t / dp}) dp")
        assertTrue(b.b > a.b + 60 * dp, "and it goes down: ${a.b / dp} dp, then ${b.b / dp} dp")
        assertTrue(b.b < h * 0.5, "not further than the upper half")
        // its little red arrow on the top bar (user 2026-11-22: "add a little red alpha=0.8 arrow on the top bar that
        // indicates the movement"): with the bar all the way out, the one shape up there, inside what has come in of
        // the bar, above the finger's way
        val p0 = HintPainter.place(Hint.SWIPE_DOWN, w, h, dp, 0.0)
        val out = at(Hint.SWIPE_DOWN, t0 + HintPainter.MOVE_FROM + HintPainter.MOVE + 0.05, 0.0).second
        val up = out.boxes.filter { it.second[3] <= 40 * dp }
        assertTrue(up.size == 1 && up[0].first == red, "one red shape on the bar: the arrow ($up)")
        val arrow = up[0].second
        assertTrue(arrow[1] >= 0 && arrow[3] <= HintPainter.BAR_PEEK * dp && arrow[3] > HintPainter.BAR_PEEK * dp * 0.6, "on the bar that has come in: ${arrow[1] / dp} to ${arrow[3] / dp} dp")
        assertTrue(arrow[0] > 440 * dp && arrow[2] < w - 64 * dp && kotlin.math.abs((arrow[0] + arrow[2]) / 2 - p0[0]) < 1.0, "on none of the bar's buttons, above the finger's way")
        // half way in, the finger and the arrow, cut off where the bar ends
        val half = at(Hint.SWIPE_DOWN, t0 + HintPainter.MOVE_FROM + HintPainter.MOVE / 2, 0.0).second
        assertTrue(half.shapes >= 2, "the finger and the arrow half way")
        // one stroke per showing (user 2026-11-22: "the slide hints being two slides each is confusing. keep it one
        // slide that is 10% slower"), and the bar back in: nothing after it until the next showing
        assertFalse(at(Hint.SWIPE_DOWN, t0 + HintPainter.plays(Hint.SWIPE_DOWN) + 0.05, 0.0).first, "one stroke, then nothing")
        assertEquals(0.495, HintPainter.MOVE, 1e-9)
        // the swipe to the side: across the floor, low, above the bottom bar when that is shown; one way in one showing,
        // the other way in the next
        val side = HintPainter.place(Hint.SWIPE_SIDE, w, h, dp, 0.0)
        val t1 = HintPainter.delay(Hint.SWIPE_SIDE)
        val c = at(Hint.SWIPE_SIDE, t1 + 0.4, 0.0).second
        assertTrue(c.t > h * 0.7 && c.b < h, "the swipe to the side is low on the floor: from ${c.t / dp} to ${c.b / dp} dp of ${h / dp}")
        assertEquals(side[1], side[3], 1e-9, "and goes straight across")
        assertTrue(at(Hint.SWIPE_SIDE, t1 + 0.4, strip).second.b < h - strip, "above the bottom bar when that is shown")
        fun sideX(t: Double): Double { val s = at(Hint.SWIPE_SIDE, t, 0.0).second; return (s.l + s.r) / 2 }
        val slid = HintPainter.MOVE_FROM + HintPainter.MOVE
        assertTrue(sideX(t1 + slid) < (side[0] + side[2]) / 2 && sideX(HintPainter.start(Hint.SWIPE_SIDE, 1) + slid) > (side[0] + side[2]) / 2, "to the left (a step on), then to the right (a step back)")
        // every flag has a name of its own; and there are three things (user 2026-11-22: "we don't need more hints, just
        // the three"): the tap (that pauses, that plays), the swipe down, the swipe to the side
        assertEquals(Hint.values().size, Hint.values().map { it.key }.toSet().size)
        assertEquals(setOf("tap_pause", "tap_play", "bars", "swipe_side"), Hint.values().map { it.key }.toSet())
    }
}
