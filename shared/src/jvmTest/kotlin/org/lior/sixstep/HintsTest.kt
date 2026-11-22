package org.lior.sixstep

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hints' pictures (HintPainter), drawn onto a canvas that only writes down what it is asked to draw: when each
 *  shows and rests, that it stays where it belongs, and that a screen is told rightly how long it may sleep. (How they
 *  look was judged by eye, on the web page.) */
class HintsTest {
    /** what was drawn: how many shapes, and the box around them */
    private class Sheet : Draw {
        var shapes = 0
        var l = Double.POSITIVE_INFINITY; var t = Double.POSITIVE_INFINITY; var r = Double.NEGATIVE_INFINITY; var b = Double.NEGATIVE_INFINITY
        val colors = HashSet<Int>()
        private val path = ArrayList<Pair<Double, Double>>()
        private fun mark(x: Double, y: Double) { l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y) }
        private fun shape(color: Int) { if (Colors.alpha(color) > 0) { shapes++; colors.add(color and 0x00FFFFFF) } }
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
        override fun save() {}
        override fun clipRect(l: Double, t: Double, r: Double, b: Double) {}
        override fun restore() {}
        override fun text(s: String, x: Double, y: Double, size: Double, color: Int, weight: FontWeight, align: TextAlign) { shape(color) }
        override fun textWidth(s: String, size: Double, weight: FontWeight): Double = s.length * size * 0.6
    }

    // a phone held sideways: 831 x 387 dp, 2.625 pixels to the dp
    private val dp = 2.625; private val w = 831 * dp; private val h = 387 * dp; private val strip = 132 * dp

    private fun at(hint: Hint, t: Double, bottomBar: Double, first: Double = HintPainter.delay(hint)): Pair<Boolean, Sheet> {
        val s = Sheet()
        return Pair(HintPainter.draw(s, hint, t, w, h, dp, bottomBar, first = first), s)
    }

    /** the common practice looked up on 2026-11-22 (HintPainter's comment): movements of 200 to 500 ms, eased; a hint
     *  that waits for the user to be idle, shows a few times and rests; still pictures where less motion is asked for */
    @Test fun everyHintWaitsShowsAFewTimesAndRests() {
        for (hint in Hint.values()) {
            val bar = if (hint == Hint.STRIP_TAP || hint == Hint.STRIP_SCROLL) strip else 0.0
            val delay = HintPainter.delay(hint)
            assertTrue(delay >= 2.0, "$hint waits for the user to leave the screen alone: $delay s")
            // before its time: nothing, and the screen is told how long that is
            val (early, nothing) = at(hint, delay - 0.5, bar)
            assertFalse(early); assertEquals(0, nothing.shapes, "$hint draws nothing before its time")
            assertEquals(0.5, HintPainter.wait(hint, delay - 0.5), 1e-9)
            // then it plays: something is drawn, in the floor's dark grey and white only, inside the screen
            var played = 0.0; var t = delay; var showings = 0; var wasPlaying = false
            val starts = ArrayList<Double>()
            while (t < delay + 40.0) {
                val (playing, s) = at(hint, t, bar)
                if (playing && !wasPlaying) { showings++; starts.add(t) }
                wasPlaying = playing
                if (playing) {
                    played += 0.01
                    assertEquals(0.0, HintPainter.wait(hint, t), 1e-9, "$hint: no sleeping while it plays")
                    if (s.shapes > 0) {
                        assertTrue(s.l >= 0 && s.t >= 0 && s.r <= w && s.b <= h, "$hint at $t s stays on the screen: ${s.l}, ${s.t}, ${s.r}, ${s.b}")
                        assertTrue(s.colors.all { it == (Colors.INK and 0x00FFFFFF) || it == (Colors.WHITE and 0x00FFFFFF) }, "$hint is drawn in the floor's grey and white")
                    }
                } else {
                    assertEquals(0, s.shapes, "$hint draws nothing while it rests")
                    val sleep = HintPainter.wait(hint, t)
                    assertTrue(sleep > 0, "$hint at $t s: the screen may sleep")
                    if (sleep.isFinite()) {
                        assertTrue(at(hint, t + sleep + 0.02, bar).first, "$hint plays again when the screen wakes (${t + sleep} s)")
                        assertFalse(sleep > 0.03 && at(hint, t + sleep - 0.02, bar).first, "$hint: and not before")
                    }
                }
                t += 0.01
            }
            // a few showings, GAP apart, each about a second or two; then it rests until the user touches the screen
            assertEquals(HintPainter.SHOWINGS, showings, "$hint shows ${HintPainter.SHOWINGS} times, then rests")
            for (i in 1 until starts.size) assertEquals(HintPainter.GAP, starts[i] - starts[i - 1], 0.02, "$hint: ${HintPainter.GAP} s from one showing to the next")
            assertTrue(HintPainter.plays(hint) in 0.8..2.0, "$hint takes ${HintPainter.plays(hint)} s: long enough to follow, short enough not to drag")
            assertEquals(HintPainter.plays(hint) * HintPainter.SHOWINGS, played, 0.05)
            val after = delay + (HintPainter.SHOWINGS - 1) * HintPainter.GAP + HintPainter.plays(hint) + 0.1
            assertEquals(Double.POSITIVE_INFINITY, HintPainter.wait(hint, after), "$hint: after its last showing the screen waits for a touch")
            assertFalse(at(hint, after + 3600.0, bar).first, "$hint does not come back by itself")
            // the same in still pictures: something is drawn while it plays, and nothing moves
            val a = Sheet(); val b = Sheet()
            assertTrue(HintPainter.draw(a, hint, delay + 0.4, w, h, dp, bar, still = true))
            assertTrue(HintPainter.draw(b, hint, delay + HintPainter.plays(hint) - 0.45, w, h, dp, bar, still = true))
            assertTrue(a.shapes > 0 && b.shapes > 0, "$hint: a still picture")
            assertEquals(a.l, b.l, 1e-9); assertEquals(a.t, b.t, 1e-9); assertEquals(a.r, b.r, 1e-9); assertEquals(a.b, b.b, 1e-9)
            assertEquals(0.0, HintPainter.nudge(hint, delay + 0.5, still = true), "$hint moves nothing in still pictures")
            // nothing in the first FIRST seconds after the floor opened (user 2026-11-22: "wait 14 seconds from starting
            // to showing the first hint"): with the whole of them still to come, the first showing is at FIRST
            val first = HintPainter.firstAt(hint, HintPainter.FIRST)
            assertEquals(14.0, first)
            assertEquals(14.0, HintPainter.wait(hint, 0.0, first), 1e-9)
            assertFalse(at(hint, 13.95, bar, first).first, "$hint: not before 14 s")
            assertTrue(at(hint, 14.05, bar, first).first, "$hint: at 14 s")
            assertTrue(at(hint, 14.05 + HintPainter.GAP, bar, first).first, "$hint: and then every ${HintPainter.GAP} s as always")
            assertEquals(delay, HintPainter.firstAt(hint, 1.0), "$hint: with less of them left than its own wait, its own wait")
        }
        // 15% longer between showings than v74's 8 s (user 2026-11-22)
        assertEquals(9.2, HintPainter.GAP, 1e-9)
        // every movement within 200 to 500 ms (NN/g, web.dev)
        assertTrue(HintPainter.MOVE in 0.2..0.5 && HintPainter.RING_LASTS in 0.2..0.5, "the slide ${HintPainter.MOVE} s, a ring ${HintPainter.RING_LASTS} s")
        assertTrue(HintPainter.APPEAR in 0.1..0.3 && HintPainter.PRESS in 0.08..0.2)
        // the user waits a few seconds after a pause before the hint to play
        assertTrue(HintPainter.delay(Hint.TAP_PLAY) >= 4.0)
    }

    /** the real interface makes a little of the move: it follows the finger as it slides and goes back as it lets go */
    @Test fun theInterfaceFollowsTheFingerALittle() {
        for (hint in listOf(Hint.SWIPE_DOWN, Hint.STRIP_SCROLL)) {
            val t0 = HintPainter.delay(hint)
            var most = 0.0; var least = 0.0
            var t = t0
            while (t < t0 + HintPainter.GAP + HintPainter.plays(hint)) { val n = HintPainter.nudge(hint, t); most = max(most, n); least = min(least, n); t += 0.005 }
            assertEquals(1.0, most, 0.05, "$hint: all the way, as the finger gets there")
            if (hint == Hint.STRIP_SCROLL) assertEquals(-1.0, least, 0.05, "the bottom bar's pictures go one way in the first showing, the other way in the next")
            else assertEquals(0.0, least, 1e-9, "the top bar only comes down")
            assertEquals(0.0, HintPainter.nudge(hint, t0 + HintPainter.STROKE - 0.01), 1e-3, "$hint: back in place as the stroke ends")
            assertEquals(0.0, HintPainter.nudge(hint, t0 + HintPainter.plays(hint) + 0.5), "$hint: nothing between showings")
        }
        for (hint in listOf(Hint.TAP_PAUSE, Hint.TAP_PLAY, Hint.STRIP_TAP, Hint.SWIPE_SIDE)) {
            for (k in 0..40) assertEquals(0.0, HintPainter.nudge(hint, HintPainter.delay(hint) + k * 0.025), "$hint moves nothing")
        }
        assertTrue(HintPainter.BAR_PEEK in 10.0..24.0 && HintPainter.STRIP_NUDGE in 16.0..40.0, "a short way (Microsoft's swipe hint: 15 px down, 23 px across)")
    }

    @Test fun theHintsAreWhereTheirGestureBelongs() {
        // the taps that pause and play: beside the dancers, low on the right, and above the bottom bar when that is shown
        for (hint in listOf(Hint.TAP_PAUSE, Hint.TAP_PLAY)) {
            val (_, s) = at(hint, HintPainter.delay(hint) + 0.45, 0.0)
            assertTrue(s.shapes >= 4, "$hint: the dot, its mark and rings (${s.shapes} shapes)")
            assertTrue(s.l > w * 0.75 && s.t > h * 0.5, "$hint is low on the right: from (${s.l / dp}, ${s.t / dp}) dp")
            val (_, raised) = at(hint, HintPainter.delay(hint) + 0.45, strip)
            assertTrue(raised.b < h - strip, "$hint keeps above the bottom bar: its lowest point ${raised.b / dp} dp of ${h / dp}")
            assertEquals(s.b - strip, raised.b, 1e-6, "by the bar's height exactly")
        }
        // the swipe down: from near the top edge, in the middle, going down
        val t0 = HintPainter.delay(Hint.SWIPE_DOWN)
        val a = at(Hint.SWIPE_DOWN, t0 + 0.15, 0.0).second
        val b = at(Hint.SWIPE_DOWN, t0 + HintPainter.MOVE_FROM + HintPainter.MOVE, 0.0).second
        assertTrue(a.l > w * 0.4 && a.r < w * 0.6 && a.t < 24 * dp, "the swipe down starts at the top, in the middle: from ${a.t / dp} dp")
        assertTrue(b.b > a.b + 60 * dp, "and it goes down: ${a.b / dp} dp, then ${b.b / dp} dp")
        assertTrue(b.b < h * 0.5, "not further than the upper half")
        // one stroke per showing (user 2026-11-22: "the slide hints being two slides each is confusing. keep it one
        // slide that is 10% slower"): nothing after it until the next showing
        assertFalse(at(Hint.SWIPE_DOWN, t0 + HintPainter.STROKE + 0.05, 0.0).first, "one stroke, then nothing")
        assertEquals(0.88, HintPainter.STROKE, 1e-9); assertEquals(0.495, HintPainter.MOVE, 1e-9)
        // the bottom bar's two: on its row of pictures
        for (hint in listOf(Hint.STRIP_TAP, Hint.STRIP_SCROLL)) {
            val s = at(hint, HintPainter.delay(hint) + 0.3, strip).second
            assertTrue(s.t > h - 100 * dp && s.b <= h, "$hint is on the bottom bar's pictures: from ${s.t / dp} to ${s.b / dp} dp of ${h / dp}")
        }
        // the drag goes one way in one showing, the other way in the next
        val p = HintPainter.place(Hint.STRIP_SCROLL, w, h, dp, strip)
        fun dotX(t: Double): Double { val s = at(Hint.STRIP_SCROLL, t, strip).second; return (s.l + s.r) / 2 }
        val s0 = HintPainter.delay(Hint.STRIP_SCROLL) + HintPainter.MOVE_FROM + HintPainter.MOVE
        assertTrue(dotX(s0) < (p[0] + p[2]) / 2, "the first stroke ends on the left")
        assertTrue(dotX(s0 + HintPainter.GAP) > (p[0] + p[2]) / 2, "the next showing's on the right")
        // the swipe to the side: across the floor, low, above the bottom bar when that is shown; one way in one showing,
        // the other way in the next
        val side = HintPainter.place(Hint.SWIPE_SIDE, w, h, dp, 0.0)
        val t1 = HintPainter.delay(Hint.SWIPE_SIDE)
        val c = at(Hint.SWIPE_SIDE, t1 + 0.4, 0.0).second
        assertTrue(c.t > h * 0.7 && c.b < h, "the swipe to the side is low on the floor: from ${c.t / dp} to ${c.b / dp} dp of ${h / dp}")
        assertTrue(c.b - c.t < 40 * dp, "and goes across, not up or down")
        assertTrue(at(Hint.SWIPE_SIDE, t1 + 0.4, strip).second.b < h - strip, "above the bottom bar when that is shown")
        fun sideX(t: Double): Double { val s = at(Hint.SWIPE_SIDE, t, 0.0).second; return (s.l + s.r) / 2 }
        val e = t1 + HintPainter.MOVE_FROM + HintPainter.MOVE
        assertTrue(sideX(e) < (side[0] + side[2]) / 2 && sideX(e + HintPainter.GAP) > (side[0] + side[2]) / 2, "to the left (a step on), then to the right (a step back)")
        // every flag has a name of its own
        assertEquals(Hint.values().size, Hint.values().map { it.key }.toSet().size)
    }
}
