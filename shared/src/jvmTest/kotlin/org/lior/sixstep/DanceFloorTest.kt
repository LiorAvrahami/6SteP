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
 *  test moves: opening, stepping (the feet travel, then the arrows come), play, the annotations 2 beats ahead, a jump to
 *  the next part, a pause, and the end of a piece. */
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

    @Test fun aSwipeWhilePlayingLandsTwoBeatsBeforeTheNextPartAndDoesNotReadIt() {
        val r = rig(exercise)
        r.floor.playPause()
        r.run(5000.0)   // into "side to side" (beat 6 to 30)
        val spokenBefore = r.player.spoken.size
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 28.0) < 0.05, "the jump lands at beat 28, 2 beats before the part at 30: ${r.player.beatNow()}")
        r.run(100.0)
        assertEquals("send out and bring back", r.host.scene?.banner, "the part's name is on the screen")
        r.run(1500.0)
        assertNull(r.host.scene?.banner, "and gone once the part has started")
        assertEquals(spokenBefore, r.player.spoken.size, "the part a swipe went to is not read aloud")
        // a tap pauses: the stop at rest, with its arrows
        r.floor.tap()
        assertFalse(r.floor.playing)
        assertTrue(r.host.current > 0)
    }

    @Test fun oneTapPausesAtOnceAndTwoTapsPlay() {
        val r = rig(exercise)
        // the bars are open: a tap closes them, and does not start anything
        r.floor.tap()
        assertFalse(r.host.bars, "a tap closes the bars")
        assertFalse(r.floor.playing)
        r.run(1000.0)
        // two taps close together: play
        r.floor.tap(); r.run(120.0)
        assertFalse(r.floor.playing, "one tap alone does not start the dance")
        r.floor.tap()
        assertTrue(r.floor.playing, "the second tap, 120 ms after the first, starts it")
        r.run(3000.0)
        // one tap: paused there and then, with nothing to wait for
        r.floor.tap()
        assertFalse(r.floor.playing, "a single tap pauses at once")
        // a second tap right behind it (the old habit of tapping twice to pause) leaves the dance paused
        r.run(150.0); r.floor.tap()
        assertFalse(r.floor.playing, "the second tap of a double tap does not start the dance again")
        // after that pair, two taps are a double tap again
        r.run(1000.0)
        r.floor.tap(); r.run(100.0); r.floor.tap()
        assertTrue(r.floor.playing)
        // the bars brought up while it plays: a tap closes them and the dance goes on; a second one close behind pauses
        r.run(1000.0)
        r.floor.swipeDown()
        assertTrue(r.host.bars)
        r.floor.tap()
        assertFalse(r.host.bars); assertTrue(r.floor.playing, "the tap that closes the bars does not pause")
        r.run(100.0); r.floor.tap()
        assertFalse(r.floor.playing, "tapped twice with the bars open: paused")
        // two taps far apart are two single taps: neither starts the dance
        r.run(1000.0); r.floor.tap(); r.run(600.0); r.floor.tap()
        assertFalse(r.floor.playing)
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
