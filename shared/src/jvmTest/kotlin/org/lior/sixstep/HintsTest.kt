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

    private fun at(hint: Hint, t: Double, bottomBar: Double): Pair<Boolean, Sheet> {
        val s = Sheet()
        return Pair(HintPainter.draw(s, hint, t, w, h, dp, bottomBar), s)
    }

    @Test fun everyHintWaitsPlaysRestsAndPlaysAgain() {
        for (hint in Hint.values()) {
            val bar = if (hint == Hint.STRIP_TAP || hint == Hint.STRIP_SCROLL) strip else 0.0
            val delay = HintPainter.delay(hint)
            assertTrue(delay >= 1.0, "$hint does not jump at a user the moment the screen changes")
            // before its time: nothing, and the screen is told how long that is
            val (early, nothing) = at(hint, delay - 0.5, bar)
            assertFalse(early); assertEquals(0, nothing.shapes, "$hint draws nothing before its time")
            assertEquals(0.5, HintPainter.wait(hint, delay - 0.5), 1e-9)
            // then it plays: something is drawn, in the floor's dark grey and white only, inside the screen
            var played = 0.0; var rested = 0.0; var t = delay
            var playsAgainAt = -1.0; var restedOnce = false; var showings = 0; var wasPlaying = false
            while (t < delay + 12.0) {
                val (playing, s) = at(hint, t, bar)
                if (playing && !wasPlaying) showings++
                wasPlaying = playing
                if (playing) {
                    played += 0.01
                    assertEquals(0.0, HintPainter.wait(hint, t), 1e-9, "$hint: no sleeping while it plays")
                    if (s.shapes > 0) {
                        assertTrue(s.l >= 0 && s.t >= 0 && s.r <= w && s.b <= h, "$hint at $t s stays on the screen: ${s.l}, ${s.t}, ${s.r}, ${s.b}")
                        assertTrue(s.colors.all { it == (Colors.INK and 0x00FFFFFF) || it == (Colors.WHITE and 0x00FFFFFF) }, "$hint is drawn in the floor's grey and white")
                    }
                    if (restedOnce && playsAgainAt < 0) playsAgainAt = t
                } else {
                    rested += 0.01; restedOnce = true
                    assertEquals(0, s.shapes, "$hint draws nothing while it rests")
                    val sleep = HintPainter.wait(hint, t)
                    assertTrue(sleep > 0, "$hint at $t s: the screen may sleep")
                    assertTrue(at(hint, t + sleep + 0.05, bar).first, "$hint plays again when the screen wakes (${t + sleep} s)")
                    assertFalse(at(hint, t + sleep - 0.05, bar).first || sleep < 0.05 && false, "$hint: and not before")
                }
                t += 0.01
            }
            // quick, and back every four seconds, for good (user 2026-11-21: "at least 4 or 5 times snappier ... every
            // hint is one animation ... make the hint come back like every 4 seconds, never give up")
            assertEquals(3, showings, "$hint shows once every ${HintPainter.EVERY} s: three times in 12 s")
            assertTrue(HintPainter.plays(hint) <= 0.7, "$hint is over in ${HintPainter.plays(hint)} s")
            assertEquals(HintPainter.plays(hint) * 3, played, 0.05, "$hint plays for ${HintPainter.plays(hint)} s each time")
            assertTrue(playsAgainAt > 0, "$hint comes again for as long as it is the floor's hint")
            assertTrue(at(hint, delay + 3600.0 + 0.1, bar).first, "$hint still comes an hour later: it never gives up")
        }
    }

    @Test fun theHintsAreWhereTheirGestureBelongs() {
        // the taps that pause and play: beside the dancers, low on the right, and above the bottom bar when that is shown
        for (hint in listOf(Hint.TAP_PAUSE, Hint.TAP_PLAY)) {
            val (_, s) = at(hint, HintPainter.delay(hint) + 0.2, 0.0)
            assertTrue(s.shapes >= 4, "$hint: the dot, its mark and rings (${s.shapes} shapes)")
            assertTrue(s.l > w * 0.75 && s.t > h * 0.5, "$hint is low on the right: from (${s.l / dp}, ${s.t / dp}) dp")
            val (_, raised) = at(hint, HintPainter.delay(hint) + 0.2, strip)
            assertTrue(raised.b < h - strip, "$hint keeps above the bottom bar: its lowest point ${raised.b / dp} dp of ${h / dp}")
            assertEquals(s.b - strip, raised.b, 1e-6, "by the bar's height exactly")
        }
        // the swipe down: from the top edge, in the middle, going down
        val a = at(Hint.SWIPE_DOWN, HintPainter.delay(Hint.SWIPE_DOWN) + 0.06, 0.0).second
        val b = at(Hint.SWIPE_DOWN, HintPainter.delay(Hint.SWIPE_DOWN) + 0.22, 0.0).second
        assertTrue(a.l > w * 0.4 && a.r < w * 0.6 && a.t < 12 * dp, "the swipe down is at the top, in the middle")
        assertTrue(b.b > a.b + 40 * dp, "and it goes down: ${a.b / dp} dp, then ${b.b / dp} dp")
        assertTrue(b.b < h * 0.5, "not further than the upper half")
        // twice in a row: the second slide starts from the top again
        val again = at(Hint.SWIPE_DOWN, HintPainter.delay(Hint.SWIPE_DOWN) + HintPainter.SLIDE + HintPainter.SLIDE_GAP + 0.06, 0.0)
        assertTrue(again.first && again.second.b < b.b - 30 * dp, "the swipe is shown a second time, from the top: ${again.second.b / dp} dp")
        assertEquals(2, HintPainter.SLIDES)
        // the bottom bar's two: on its row of pictures
        for (hint in listOf(Hint.STRIP_TAP, Hint.STRIP_SCROLL)) {
            val s = at(hint, HintPainter.delay(hint) + 0.15, strip).second
            assertTrue(s.t > h - 100 * dp && s.b <= h, "$hint is on the bottom bar's pictures: from ${s.t / dp} to ${s.b / dp} dp of ${h / dp}")
        }
        // the drag goes one way, then the other
        fun dotAt(t: Double): Double { val p = HintPainter.place(Hint.STRIP_SCROLL, w, h, dp, strip); val s = at(Hint.STRIP_SCROLL, t, strip).second; return if (kotlin.math.abs(s.l - min(p[0], p[2])) < kotlin.math.abs(s.r - max(p[0], p[2]))) -1.0 else 1.0 }
        val first = HintPainter.delay(Hint.STRIP_SCROLL) + 0.2
        assertTrue(dotAt(first) != dotAt(first + HintPainter.SLIDE + HintPainter.SLIDE_GAP), "the bottom bar's drag is shown to the left and then to the right, in one showing")
        // every flag has a name of its own
        assertEquals(Hint.values().size, Hint.values().map { it.key }.toSet().size)
    }
}
