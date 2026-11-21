package org.lior.sixstep

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dance floor's logic on the shipped daily exercise, with a screen and a sound made of nothing but a clock that the
 *  test moves: opening, stepping (the feet travel, then the arrows come), play, the annotations 2 beats ahead, a swipe
 *  to the next part and its lead-in of rock triple triples, a tap that plays and pauses, and the end of a piece. */
class DanceFloorTest {
    private val assets = File("../app/src/main/assets/dance")
    private val data = DanceData.parse(File(assets, "skeletons.json").readText())
    private val store = CompositionStore(File(assets, "compositions.json").readText())

    /** time, in ms: only the test moves it */
    private class Clock { var ms = 0.0 }

    private class Host(val clock: Clock) : FloorHost {
        var scene: Scene? = null
        var cells = 0
        var callouts: List<Pair<Int, String>> = emptyList()
        var current = -1
        var bars = false
        val banners = ArrayList<Pair<String, Double>>()   // every banner as it came up, with nothing else: filled by the test
        private val frames = ArrayList<() -> Unit>()
        override fun nowMs() = clock.ms
        override fun nextFrame(callback: () -> Unit) { frames.add(callback) }
        override fun show(scene: Scene) { this.scene = scene }
        override fun strip(cells: List<StepCell>, callouts: List<Pair<Int, String>>) { this.cells = cells.size; this.callouts = callouts }
        override fun stripCurrent(k: Int) { current = k }
        override fun stripScrollTo(k: Int, animate: Boolean) {}
        override fun stripFollow(k: Int) {}
        override fun bars(shown: Boolean) { bars = shown }
        override fun buttons() {}
        override fun message(text: String) {}
        /** one frame of the screen */
        fun frame() { val due = frames.toList(); frames.clear(); for (f in due) f() }
    }

    /** a steady tempo on the test's clock, no music */
    private class Player(val clock: Clock, val tempo: Double) : FloorPlayer {
        override var playing = false
        override val muted = false
        override val song = MusicPlan.ALL
        override val bpmOffset = 0
        override val tracks = emptyList<Track>()
        override val loading = false
        private var beat0 = 0.0
        private var ms0 = 0.0
        val spoken = ArrayList<Pair<String, Double>>()   // what was read aloud, and at which beat
        override fun beatNow() = if (playing) beat0 + (clock.ms - ms0) / 60000.0 * tempo else beat0
        override fun bpm() = tempo
        override fun currentTrack(): Track? = null
        override fun swing(): Double? = null
        override fun play(b: Double) { playing = true; beat0 = b; ms0 = clock.ms }
        override fun stop() { beat0 = beatNow(); playing = false }
        override fun setMuted(m: Boolean) {}
        override fun chooseSong(key: String) {}
        override fun setBpmOffset(off: Int) {}
        override fun prefetch(texts: Collection<String>) {}
        override fun speak(text: String) { spoken.add(Pair(text, beatNow())) }
        override var boundaryAfter: (Double) -> Double? = { null }
        override var onChange: (() -> Unit)? = null
        override var onFocusLoss: (() -> Unit)? = null
        override var onMessage: ((String) -> Unit)? = null
        override fun readSettings() {}
        override fun release() {}
    }

    private class Rig(val clock: Clock, val host: Host, val player: Player, val floor: DanceFloor) {
        /** let `ms` pass, a frame every 10 ms; `each` sees the floor after every frame */
        fun run(ms: Double, each: (() -> Unit)? = null) {
            var left = ms
            while (left > 0) { clock.ms += 10.0; left -= 10.0; host.frame(); each?.invoke() }
        }
    }

    private fun rig(name: String, kind: String = Piece.COMPOSITION, autoplay: Boolean = false, tempo: Double = 120.0): Rig {
        val clock = Clock(); val host = Host(clock); val player = Player(clock, tempo)
        val floor = DanceFloor(data, store, kind, name, 0, autoplay, player, host)
        assertNull(floor.error, "the floor cannot dance $name")
        floor.open()
        return Rig(clock, host, player, floor)
    }

    private val exercise: String get() = store.daily()!!.name

    @Test fun itOpensOnTheStartPositionWithTheBarsShown() {
        val r = rig(exercise)
        assertEquals(Texts.START_POSITION, r.host.scene?.title)
        assertTrue(r.host.bars, "the bars are shown")
        assertEquals(0, r.host.current)
        assertTrue(r.host.cells > 100, "a picture for every stop")
        assertTrue(r.host.callouts.any { it.second == "side to side" }, "the annotations are in the bottom bar, without their counts: ${r.host.callouts.take(3)}")
        assertFalse(r.floor.playing)
    }

    @Test fun aSwipeMakesTheFeetTravelAndThenTheArrowsCome() {
        val r = rig(exercise)
        r.floor.swipe(true)
        assertEquals(1, r.host.current, "the next stop is framed at once")
        assertTrue(r.floor.travelling)
        assertEquals(0, r.host.scene!!.arrows.size, "no arrows while the feet travel")
        r.run(80.0)
        assertTrue(r.floor.travelling, "still travelling after 80 ms (a third of a beat at 120 BPM is 167 ms)")
        assertEquals(0, r.host.scene!!.arrows.size)
        r.run(200.0)
        assertFalse(r.floor.travelling)
        assertTrue(r.host.scene!!.arrows.isNotEmpty(), "the arrows come once the feet are there (count 1: both step back)")
        assertEquals("1", r.host.scene!!.count)
        // every travel takes the same time, whatever the step: a whole beat, or the short second step of a triple
        for (k in 2..9) {
            r.floor.swipe(true)
            assertEquals(k, r.host.current)
            r.run(150.0); assertTrue(r.floor.travelling, "the travel to stop $k is still under way after 150 ms")
            assertEquals(0, r.host.scene!!.arrows.size)
            r.run(40.0); assertFalse(r.floor.travelling, "the travel to stop $k is over after 190 ms")
        }
        // back: the same travel backwards, to the stop before
        r.floor.swipe(false)
        assertEquals(8, r.host.current)
        r.run(150.0); assertTrue(r.floor.travelling)
        r.run(40.0); assertFalse(r.floor.travelling)
        // a swipe in the middle of a travel cuts it short and starts the next one
        r.floor.swipe(true); r.run(50.0); r.floor.swipe(true)
        assertEquals(10, r.host.current)
        r.run(150.0); assertTrue(r.floor.travelling)
        r.run(40.0); assertFalse(r.floor.travelling)
    }

    @Test fun playShowsEachAnnotationTwoBeatsAheadAndReadsItWithItsCount() {
        val r = rig(exercise)
        r.floor.playPause()
        assertTrue(r.floor.playing)
        assertFalse(r.host.bars, "the bars close when play starts")
        var up: Double? = null; var gone: Double? = null
        r.run(8000.0) {   // 8 s at 120 BPM: 16 beats from the count-in
            val b = r.player.beatNow(); val banner = r.host.scene?.banner
            if (banner == "side to side" && up == null) up = b
            if (up != null && gone == null && banner == null) gone = b
        }
        assertNotNull(up, "the annotation of the first part came up")
        assertTrue(abs(up!! - 4.0) < 0.1, "it came up 2 beats before the part (which starts on beat 6): at beat $up")
        assertTrue(abs(gone!! - 6.0) < 0.1, "it went as the part started: at beat $gone")
        assertEquals("side to side, 3 times", r.player.spoken.first().first, "the voice says how many times")
        assertTrue(abs(r.player.spoken.first().second - 4.0) < 0.1)
        assertEquals(0, r.host.scene!!.arrows.size, "no arrows while the dance plays")
    }

    @Test fun aSwipeWhilePlayingGoesToTheNextPartThroughTwoRockTripleTriples() {
        val r = rig(exercise)
        r.floor.playPause()
        r.run(5000.0)   // into "side to side" (beat 6 to 30)
        val spokenBefore = r.player.spoken.size
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 18.0) < 0.05, "the swipe lands 12 beats before the part at 30: ${r.player.beatNow()}")
        // the lead-in: rock step, triple step, triple step, twice, with their own counts; no step of the piece is framed
        val titles = ArrayList<String>(); val counts = ArrayList<String>()
        var bannerUp: Double? = null
        r.run(5900.0) {   // to beat 29.8
            val sc = r.host.scene!!
            if (titles.lastOrNull() != sc.title) titles.add(sc.title)
            if (counts.lastOrNull() != sc.count) counts.add(sc.count)
            if (sc.banner != null && bannerUp == null) bannerUp = r.player.beatNow()
            assertEquals(-1, r.host.current, "during the lead-in no step of the piece is the current one")
            assertEquals(0, sc.arrows.size)
        }
        val rock = "rock step" + Texts.LEAD_IN_SUFFIX; val triple = "triple step" + Texts.LEAD_IN_SUFFIX
        assertEquals(listOf(rock, triple, rock, triple), titles, "what the dancers dance on their way in")
        assertEquals(listOf("1", "2", "3", "3&", "4", "5", "5&", "6", "1", "2", "3", "3&", "4", "5", "5&", "6"), counts)
        assertTrue(abs(bannerUp!! - 28.0) < 0.1, "the part's name comes up 2 beats before the part: at beat $bannerUp")
        assertEquals("send out and bring back", r.host.scene?.banner)
        // the part itself: the composition's own picture again, its name gone, its steps framed below
        r.run(200.0)
        assertFalse(r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX), "at beat ${r.player.beatNow()} the part is danced: ${r.host.scene?.title}")
        assertNull(r.host.scene?.banner, "the name is gone once the part has started")
        assertTrue(r.host.current > 0)
        assertEquals(spokenBefore, r.player.spoken.size, "the part a swipe went to is not read aloud")
        // a tap pauses: the stop at rest, with its arrows
        r.floor.tap()
        assertFalse(r.floor.playing)
        assertTrue(r.host.current > 0)
    }

    @Test fun swipesDuringALeadInGoOnePartFurtherAndAPauseStandsAtThePart() {
        val r = rig(exercise)
        r.floor.playPause()
        r.run(5000.0)                       // in "side to side" (6 to 30)
        r.floor.swipe(true); r.run(500.0)   // on the way into "send out and bring back" (30)
        assertTrue(abs(r.player.beatNow() - 19.0) < 0.1)
        // forward again: the part after it, not the same one once more
        r.floor.swipe(true)
        val second = r.player.beatNow() + 12.0
        assertTrue(second > 31.0, "the next part starts at beat $second")
        r.run(500.0)
        // back, still on the way in: the part before (the one at 30), by its own lead-in
        r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 18.0) < 0.05, "back from a lead-in goes to the part before: ${r.player.beatNow()}")
        // and back again: "side to side" at beat 6 has only one rock triple triple of dance before it, so it gets one
        r.run(300.0); r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 0.0) < 0.05, "a part 6 beats into the dance is entered from beat 0: ${r.player.beatNow()}")
        r.run(1000.0)
        assertTrue(r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX))
        // and once more: the very beginning
        r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 0.0) < 0.05)
        r.run(500.0)
        assertFalse(r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX), "the beginning is the piece itself: ${r.host.scene?.title}")
        // a pause in a lead-in: the dance stands on the first step of the part it was going to
        r.floor.swipe(true); r.run(1000.0)                     // towards "side to side" again
        assertTrue(r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX))
        r.floor.tap()
        assertFalse(r.floor.playing)
        val stood = r.host.current
        assertTrue(r.host.scene!!.title.contains("side to side"), "paused in a lead-in, the dance stands on the part's first step: ${r.host.scene?.title}")
        assertEquals("3", r.host.scene?.count, "which is the first step of side to side's triple step")
        assertFalse(r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX))
        // play from there: the part, announced as from any start
        val spoken = r.player.spoken.size
        r.floor.tap()
        assertTrue(r.floor.playing)
        r.run(300.0)
        assertTrue(r.host.current >= stood)
        assertTrue(r.player.spoken.size > spoken, "started on a part's first step by hand, its name is read")
        // a tap on a step of the bottom bar while it plays goes straight there, without a lead-in
        r.floor.tapStop(60); r.run(100.0)
        assertFalse(r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX))
        assertTrue(r.host.current >= 60)
    }

    /** every part of every shipped composition, reached by swipes: the lead-in ends in the very picture the part
     *  starts with (no foot jumps when the composition's own steps take over) */
    @Test fun aLeadInFlowsIntoItsPartWithoutAJump() {
        for (c in store.compositions) {
            val r = rig(c.name, tempo = 240.0)
            r.floor.playPause()
            fun tick() { r.clock.ms += 1.0; r.host.frame() }   // a frame every millisecond: 0.004 beats
            repeat(1500) { tick() }
            var parts = 0; var worst = 0.0; var worstAt = ""; var withLead = 0
            while (true) {
                val before = r.player.beatNow()
                r.floor.swipe(true)
                if (abs(r.player.beatNow() - before) < 1e-9) break   // no part after the last one
                parts++
                tick()
                if (!r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX)) { repeat(600) { tick() }; continue }   // a part without a lead-in
                withLead++
                var last = r.host.scene!!
                var guard = 0
                while (r.host.scene!!.title.endsWith(Texts.LEAD_IN_SUFFIX) && guard++ < 20000) { last = r.host.scene!!; tick() }
                val first = r.host.scene!!
                for ((foot, p) in first.feet) {
                    val q = last.feet[foot]!!
                    val d = kotlin.math.hypot(p.x - q.x, p.y - q.y)
                    if (d > worst) { worst = d; worstAt = "${first.title}, foot $foot" }
                }
                repeat(300) { tick() }
            }
            println("lead-ins of \"${c.name}\": $parts parts, $withLead with a lead-in, the largest step of a foot where the piece takes over: ${"%.2f".format(worst)} cm ($worstAt)")
            assertTrue(parts > 0 || c.name.contains("facing"), "${c.name} has parts to swipe to")
            assertTrue(worst < 1.0, "${c.name}: a foot jumps $worst cm where the lead-in hands over to $worstAt")
        }
    }

    @Test fun aTapPlaysAndATapPauses() {
        val r = rig(exercise)
        assertTrue(r.host.bars, "opened standing, with the bars")
        r.floor.tap()
        assertTrue(r.floor.playing, "a tap starts the dance")
        assertFalse(r.host.bars, "and play closes the bars")
        r.run(3000.0)
        r.floor.tap()
        assertFalse(r.floor.playing, "a tap pauses, at once")
        r.run(150.0)
        r.floor.tap()
        assertTrue(r.floor.playing, "and the next tap, however soon, continues the dance: there is no double tap")
        // the bars brought up while it plays: a tap still pauses; a swipe up closes them
        r.run(1000.0)
        r.floor.swipeDown()
        assertTrue(r.host.bars)
        r.floor.tap()
        assertFalse(r.floor.playing)
        assertTrue(r.host.bars, "a tap does not close the bars")
        r.floor.swipeUp()
        assertFalse(r.host.bars)
    }

    @Test fun theSongMenuHasPlayAllFirstAndShufflePlayLast() {
        val r = rig(exercise)
        val menu = r.floor.songChoices()
        assertEquals(Texts.PLAY_ALL, menu.first().label)
        assertTrue(menu.first().checked, "Play all is what is chosen until something else is")
        assertEquals(Texts.SHUFFLE_PLAY, menu.last().label)
        assertFalse(menu.last().checked)
        assertFalse(menu.any { it.key == DanceFloor.UPLOAD }, "no Upload line where songs cannot be added")
    }

    @Test fun aCompositionPlaysToItsEndAndSaysDone() {
        val r = rig(CompositionStore.QUICK_RECAP, tempo = 600.0)   // very fast, so that the test is short
        r.floor.playPause()
        r.run(20000.0)
        assertFalse(r.floor.playing, "it stopped at its end")
        assertEquals(Texts.DONE, r.host.scene?.banner)
        assertTrue(r.host.bars, "the bars come back")
        assertEquals(r.host.cells - 1, r.host.current, "on the last stop")
    }

    @Test fun anElementLoops() {
        val name = data.names.first { it.equals("tak turn", ignoreCase = true) }
        val r = rig(name, kind = Piece.ELEMENT, tempo = 240.0)
        r.floor.playPause()
        r.run(15000.0)   // 60 beats: ten times round a six-beat element
        assertTrue(r.floor.playing, "an element goes on until it is paused")
        assertTrue(r.player.beatNow() > 50)
    }
}
