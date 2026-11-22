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
 *  to the next part and its lead-in of two triple steps on the spot, a tap that plays and pauses, and the end of a piece. */
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
        override var leadIn = true   // the settings' switch for the lead-in after a swipe
        // the hints: what this user has done before (kept between floors, as a phone keeps it), and what the floor asks to be shown
        var known: HashSet<String>? = null   // null: a screen that keeps nothing, and so shows no hints
        var hint: Hint? = null
        val hints = ArrayList<Hint?>()       // every hint the floor asked for, in order
        override fun knows(what: String) = known?.contains(what) ?: true
        override fun learned(what: String) { known?.add(what) }
        override fun hint(hint: Hint?) { this.hint = hint; hints.add(hint) }
        override var stripScrolls = true
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
        var plays = 0   // how often the floor started the sound afresh (a swipe that goes nowhere does not)
        override fun play(b: Double) { plays++; playing = true; beat0 = b; ms0 = clock.ms }
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

    private fun rig(name: String, kind: String = Piece.COMPOSITION, autoplay: Boolean = false, tempo: Double = 120.0, known: HashSet<String>? = null): Rig {
        val clock = Clock(); val host = Host(clock); val player = Player(clock, tempo)
        host.known = known
        val floor = DanceFloor(data, store, kind, name, 0, autoplay, player, host)
        assertNull(floor.error, "the floor cannot dance $name")
        floor.open()
        return Rig(clock, host, player, floor)
    }

    private val exercise: String get() = store.daily()!!.name

    /** user 2026-11-21: "I don't like the fact that you start a new dance with the bars open. I prefer the bars closed
     *  and a hint to swipe down to open them" */
    @Test fun itOpensOnTheStartPositionWithTheBarsAway() {
        val r = rig(exercise)
        assertEquals(Texts.START_POSITION, r.host.scene?.title)
        assertFalse(r.host.bars, "the bars are away, as when it plays")
        assertFalse(r.floor.barsShown)
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

    /** user 2026-11-21: "the lead in is going to be just 2 triple steps ... completely in place. The legs don't need to
     *  move at all ... You just change the weight from one leg to the other ... It's more like a pause, and it's
     *  connected to the beat" */
    @Test fun aSwipeWhilePlayingLeadsInWithTwoTripleStepsOnTheSpot() {
        val r = rig(exercise)
        r.floor.playPause()
        r.run(5000.0)   // into "side to side" (beat 6 to 30)
        val spokenBefore = r.player.spoken.size
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 26.0) < 0.05, "the swipe lands 4 beats before the part at 30: ${r.player.beatNow()}")
        r.run(10.0)
        val stand = r.host.scene!!.feet   // where the dancers stand: where the part starts
        // four beats in which no foot moves and the weight goes from leg to leg; no step of the piece is framed
        val titles = ArrayList<String>(); val counts = ArrayList<String>()
        val weights = ArrayList<Triple<Double, Map<Char, Double>, Map<Char, Double>>>()   // (beat, his, hers)
        var bannerUp: Double? = null
        r.run(1890.0) {   // to beat 29.8
            val sc = r.host.scene!!; val b = r.player.beatNow()
            if (titles.lastOrNull() != sc.title) titles.add(sc.title)
            if (counts.lastOrNull() != sc.count) counts.add(sc.count)
            if (sc.banner != null && bannerUp == null) bannerUp = b
            weights.add(Triple(b, sc.weights['L']!!, sc.weights['F']!!))
            assertEquals(-1, r.host.current, "during the lead-in no step of the piece is the current one")
            assertEquals(0, sc.arrows.size)
            // until the foot of the part's first step leaves for it (0.7 of a beat before the part), nothing moves at all
            if (b < 29.25) for ((foot, p) in sc.feet) {
                val q = stand[foot]!!
                assertTrue(abs(p.x - q.x) < 1e-9 && abs(p.y - q.y) < 1e-9 && abs(p.h - q.h) < 1e-9, "at beat $b the foot $foot has moved: $p, it stood at $q")
            }
        }
        // named with the part it leads to, from the first moment on
        val triple = "triple step — lead-in to send out and bring back"
        assertEquals(triple, Texts.leadIn("triple step", "send out and bring back"))
        assertEquals("triple step — lead-in", Texts.leadIn("triple step", ""), "a part without a name: only that it is a lead-in")
        assertEquals(listOf(triple), titles, "two triple steps, nothing else")
        assertEquals(listOf("3", "3&", "4", "5", "5&", "6"), counts, "counted as two triple steps are")
        // the weight: he starts on his left (the foot the part starts with, free there), she on her right; each ends with that foot free again
        fun at(beat: Double) = weights.minByOrNull { abs(it.first - beat) }!!
        val his = listOf(26.45 to 'L', 26.92 to 'R', 27.5 to 'L', 28.5 to 'R', 28.92 to 'L', 29.5 to 'R')
        for ((beat, foot) in his) {
            val w = at(beat)
            val other = if (foot == 'L') 'R' else 'L'
            assertTrue(w.second[foot]!! > 0.8 && w.second[other]!! < 0.2, "at beat $beat his weight is on his $foot foot: ${w.second}")
            assertTrue(w.third[other]!! > 0.8 && w.third[foot]!! < 0.2, "at beat $beat her weight is on her $other foot: ${w.third}")
        }
        assertTrue(abs(bannerUp!! - 28.0) < 0.1, "the part's name comes up 2 beats before the part: at beat $bannerUp")
        assertEquals("send out and bring back", r.host.scene?.banner)
        // the part itself: the composition's own picture again, its name gone, its steps framed below
        r.run(200.0)
        assertFalse(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX), "at beat ${r.player.beatNow()} the part is danced: ${r.host.scene?.title}")
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
        assertTrue(abs(r.player.beatNow() - 27.0) < 0.1)
        // forward again: the part after it, not the same one once more
        r.floor.swipe(true)
        val second = r.player.beatNow() + DanceFloor.LEAD_BEATS
        assertTrue(second > 31.0, "the next part starts at beat $second")
        r.run(500.0)
        // back, still on the way in: the part before (the one at 30), by its own lead-in
        r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 26.0) < 0.05, "back from a lead-in goes to the part before: ${r.player.beatNow()}")
        // and back again: "side to side" at beat 6
        r.run(300.0); r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 2.0) < 0.05, "a part 6 beats into the dance is entered from beat 2: ${r.player.beatNow()}")
        r.run(1000.0)
        assertTrue(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX))
        // and once more: the very beginning
        r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 0.0) < 0.05)
        r.run(500.0)
        assertFalse(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX), "the beginning is the piece itself: ${r.host.scene?.title}")
        // a pause in a lead-in: the dance stands on the first step of the part it was going to
        r.floor.swipe(true); r.run(1000.0)                     // towards "side to side" again
        assertTrue(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX))
        r.floor.tap()
        assertFalse(r.floor.playing)
        val stood = r.host.current
        assertTrue(r.host.scene!!.title.contains("side to side"), "paused in a lead-in, the dance stands on the part's first step: ${r.host.scene?.title}")
        assertEquals("3", r.host.scene?.count, "which is the first step of side to side's triple step")
        assertFalse(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX))
        // play from there: the part, announced as from any start
        val spoken = r.player.spoken.size
        r.floor.tap()
        assertTrue(r.floor.playing)
        r.run(300.0)
        assertTrue(r.host.current >= stood)
        assertTrue(r.player.spoken.size > spoken, "started on a part's first step by hand, its name is read")
        // a tap on a step of the bottom bar while it plays goes straight there, without a lead-in
        r.floor.tapStop(60); r.run(100.0)
        assertFalse(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX))
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
                val before = r.player.plays
                r.floor.swipe(true)
                if (r.player.plays == before) break   // no part after the last one
                parts++
                tick()
                if (!r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX)) { repeat(600) { tick() }; continue }   // a part without a lead-in
                withLead++
                var last = r.host.scene!!
                var guard = 0
                while (r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX) && guard++ < 20000) { last = r.host.scene!!; tick() }
                val first = r.host.scene!!
                for ((foot, p) in first.feet) {
                    val q = last.feet[foot]!!
                    val d = kotlin.math.hypot(p.x - q.x, p.y - q.y)
                    if (d > worst) { worst = d; worstAt = "${first.title}, foot $foot" }
                }
                repeat(300) { tick() }
            }
            println("lead-ins of \"${c.name}\": $parts parts, $withLead with a lead-in, the largest step of a foot where the piece takes over: ${"%.2f".format(worst)} cm ($worstAt)")
            assertTrue(parts > 0, "${c.name} has parts to swipe to")
            assertEquals(parts, withLead, "${c.name}: every part that a swipe goes forward to has four beats before it, and so a lead-in")
            assertTrue(worst < 1.0, "${c.name}: a foot jumps $worst cm where the lead-in hands over to $worstAt")
        }
    }

    /** the settings' switch off (user 2026-11-21: "make this feature toggleable ... so that it's possible to disable
     *  it"): a swipe goes straight to the part, as every swipe did before the lead-in existed */
    @Test fun withTheLeadInSwitchedOffASwipeGoesStraightToThePart() {
        val r = rig(exercise)
        r.host.leadIn = false
        r.floor.playPause()
        r.run(5000.0)   // into "side to side" (beat 6 to 30)
        val spokenBefore = r.player.spoken.size
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 28.0) < 0.05, "the swipe lands 2 beats before the part at 30: ${r.player.beatNow()}")
        r.run(100.0)
        val sc = r.host.scene!!
        assertFalse(sc.title.contains(Texts.LEAD_IN_SUFFIX), "no rock triple triples: the composition's own last steps (${sc.title})")
        assertEquals("send out and bring back", sc.banner, "the part's name is up at once")
        assertTrue(r.host.current > 0, "a step of the piece is framed below")
        r.run(1100.0)   // past beat 30
        assertNull(r.host.scene?.banner, "the name is gone once the part has started")
        assertTrue(r.host.scene!!.title.startsWith("send out"), "the part is danced: ${r.host.scene?.title}")
        assertEquals(spokenBefore, r.player.spoken.size, "the part a swipe went to is written, not read aloud")
        // back, in the part's first seconds: the part before, again 2 beats ahead of it
        r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 4.0) < 0.05, "back in a part's first seconds goes to the part before: ${r.player.beatNow()}")
        // forward twice in a row: a part each time
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 28.0) < 0.05, "forward: ${r.player.beatNow()}")
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 64.0) < 0.05, "and forward again, to the part at 66: ${r.player.beatNow()}")
        r.run(100.0)
        assertFalse(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX))
        // well into a part, back goes to its own start
        r.run(4000.0)   // beat 72.2: more than 2 s after the swipe landed
        r.floor.swipe(false)
        assertTrue(abs(r.player.beatNow() - 64.0) < 0.05, "back, well into the part: its own start again: ${r.player.beatNow()}")
        // switched on again, the next swipe leads in
        r.run(2000.0)   // into the part
        r.host.leadIn = true
        r.floor.swipe(true)
        assertTrue(abs(r.player.beatNow() - 158.0) < 0.05, "switched on again, a swipe lands 4 beats before the part at 162: ${r.player.beatNow()}")
        r.run(50.0)
        assertTrue(r.host.scene!!.title.contains(Texts.LEAD_IN_SUFFIX), "and the dancers lead in: ${r.host.scene?.title}")
    }

    /** user 2026-11-21: "a series of flags ... that indicate what the user has already done. And once a user has already
     *  used some functionality, then we don't need to hint at it anymore. If the user didn't use some functionality,
     *  then we keep hinting at this functionality to the user until they use this functionality." */
    @Test fun aNewUserIsShownOneHintAtATimeUntilEachThingHasBeenDone() {
        val known = HashSet<String>()   // a new user: nothing done yet
        val r = rig(exercise, autoplay = true, known = known)
        // the daily exercise plays, the bars are away: a tap would pause it
        assertTrue(r.floor.playing && !r.floor.barsShown)
        assertEquals(Hint.TAP_PAUSE, r.host.hint)
        r.run(4000.0)
        assertEquals(Hint.TAP_PAUSE, r.host.hint, "it stays for as long as the dance plays and the tap has not been used")
        assertTrue(known.isEmpty())
        // paused with the play button of the top bar (the bars are away, so only a test can): that is not the tap
        r.floor.playPause()
        assertEquals(Hint.TAP_PLAY, r.host.hint, "standing, the hint is the tap that plays")
        r.floor.playPause()
        assertTrue(known.isEmpty(), "pausing and playing another way raises no flag")
        assertEquals(Hint.TAP_PAUSE, r.host.hint)
        // the tap: the flag goes up, and now a tap would play
        r.floor.tap()
        assertFalse(r.floor.playing)
        assertEquals(setOf("tap_pause"), known)
        assertEquals(Hint.TAP_PLAY, r.host.hint)
        r.floor.tap()
        assertTrue(r.floor.playing)
        assertEquals(setOf("tap_pause", "tap_play"), known)
        // both taps are known: what is left to find with the bars away is the swipe down
        assertEquals(Hint.SWIPE_DOWN, r.host.hint)
        r.floor.tap(); assertEquals(Hint.SWIPE_DOWN, r.host.hint, "paused or playing")
        r.floor.swipeDown()
        assertTrue(r.floor.barsShown)
        assertEquals(setOf("tap_pause", "tap_play", "bars"), known)
        // the bars are there: a step of the bottom bar can be tapped
        assertEquals(Hint.STRIP_TAP, r.host.hint)
        // (a second swipe sends the bars away again, and with them the bottom bar's hint; the next one brings both back)
        r.floor.swipeDown(); assertFalse(r.floor.barsShown); assertNull(r.host.hint, "the bars away, and nothing left to find without them")
        r.floor.swipeUp(); assertTrue(r.floor.barsShown); assertEquals(Hint.STRIP_TAP, r.host.hint)
        r.floor.tapStop(7)
        assertEquals(7, r.host.current)
        assertTrue("strip_tap" in known)
        // and the bar can be dragged
        assertEquals(Hint.STRIP_SCROLL, r.host.hint)
        r.floor.stripScrolled()
        assertEquals(setOf("tap_pause", "tap_play", "bars", "strip_tap", "strip_scroll"), known)
        assertNull(r.host.hint, "everything has been done: no hint is left")
        // and none comes back, whatever happens on the floor
        val asked = r.host.hints.size
        r.floor.tap(); r.run(500.0); r.floor.tap(); r.floor.swipeDown(); r.floor.swipeUp(); r.floor.tap(); r.floor.swipe(true); r.run(500.0)
        assertEquals(asked, r.host.hints.size, "the floor asks for no hint any more")
        // nor on another floor, on another day: the flags are the screen's, kept for good
        val again = rig(exercise, autoplay = true, known = known)
        again.run(4000.0); again.floor.tap(); again.floor.swipeDown()
        assertTrue(again.host.hints.all { it == null }, "a user who has done it all is shown nothing: ${again.host.hints}")
        // one hint at a time, each replaced by the next (the first two came twice: once for the top bar's button, which
        // paused and played without teaching the tap, and once for the taps themselves; and the bottom bar's first one
        // went and came with the bars)
        assertEquals(listOf(Hint.TAP_PAUSE, Hint.TAP_PLAY, Hint.TAP_PAUSE, Hint.TAP_PLAY, Hint.SWIPE_DOWN, Hint.STRIP_TAP, null, Hint.STRIP_TAP, Hint.STRIP_SCROLL, null),
            r.host.hints.take(asked).filterIndexed { i, h -> i == 0 || r.host.hints[i - 1] != h })
    }

    /** opened from the explore page a piece stands on its first step, the bars away: the first hint there is the swipe
     *  down (the user's wish); the tap that plays follows once the bars have been found */
    @Test fun aPieceThatHasJustBeenOpenedHintsAtTheSwipeDownFirst() {
        val known = HashSet<String>()
        val r = rig("side to side", kind = Piece.BLOCK, known = known)
        assertTrue(!r.floor.barsShown && !r.floor.playing)
        assertEquals(Hint.SWIPE_DOWN, r.host.hint, "just opened and standing: first the way to the bars")
        r.floor.swipeDown()
        assertTrue(r.floor.barsShown)
        assertEquals(setOf("bars"), known)
        // the bars are there: the bottom bar's two, one after the other
        assertEquals(Hint.STRIP_TAP, r.host.hint)
        r.floor.tapStop(2)
        assertEquals(Hint.STRIP_SCROLL, r.host.hint)
        // a bar whose steps all fit on the screen cannot be dragged: no hint for that
        val clock = Clock(); val host = Host(clock); host.known = hashSetOf("strip_tap", "bars"); host.stripScrolls = false
        val floor = DanceFloor(data, store, Piece.BLOCK, "side to side", 0, false, Player(clock, 120.0), host)
        floor.open(); floor.swipeDown()
        assertEquals(Hint.TAP_PLAY, host.hint, "with nothing to drag, the next thing is the tap that plays")
        // the user drags the bar: now the tap that plays, on the floor between the bars
        r.floor.stripScrolled()
        assertEquals(Hint.TAP_PLAY, r.host.hint)
        r.floor.tap()
        assertTrue(r.floor.playing && !r.floor.barsShown, "the tap plays, and the bars go")
        assertEquals(Hint.TAP_PAUSE, r.host.hint)
        r.floor.tap()
        assertEquals(setOf("bars", "strip_tap", "strip_scroll", "tap_play", "tap_pause"), known)
        assertNull(r.host.hint, "everything has been done")
        // someone who finds the tap first: the dance plays, and then the hint is the tap that pauses, not the swipe
        val k2 = HashSet<String>()
        val r2 = rig("side to side", kind = Piece.BLOCK, known = k2)
        assertEquals(Hint.SWIPE_DOWN, r2.host.hint)
        r2.floor.tap()
        assertTrue(r2.floor.playing)
        assertEquals(setOf("tap_play"), k2)
        assertEquals(Hint.TAP_PAUSE, r2.host.hint, "while it plays: the tap that pauses")
        r2.floor.tap()
        assertEquals(Hint.SWIPE_DOWN, r2.host.hint, "both taps known: the swipe down is what is left")
        // and someone who only watched: paused with the top bar's button after playing, the first hint is the tap that plays
        val k3 = HashSet<String>()
        val r3 = rig(exercise, autoplay = true, known = k3)
        assertEquals(Hint.TAP_PAUSE, r3.host.hint)
        r3.floor.playPause()
        assertEquals(Hint.TAP_PLAY, r3.host.hint, "after a pause: the tap that plays comes before the swipe (and waits a few seconds, see HintPainter.delay)")
        assertTrue(HintPainter.delay(Hint.TAP_PLAY) >= 3.0, "whoever paused is left alone for a few seconds")
        // leaving the floor takes the hint away
        r3.floor.release()
        assertNull(r3.host.hint)
    }

    /** a hint never plays while the user is busy: every touch restarts its clock (the screen is told the same hint
     *  again), and with no hint on the screen a touch tells it nothing */
    @Test fun aTouchRestartsTheHintsClock() {
        val r = rig(exercise, autoplay = true, known = HashSet())
        assertEquals(Hint.TAP_PAUSE, r.host.hint)
        val before = r.host.hints.size
        r.floor.touched()
        assertEquals(before + 1, r.host.hints.size, "the screen is told again")
        assertEquals(Hint.TAP_PAUSE, r.host.hints.last(), "the same hint, its clock started afresh")
        val done = rig(exercise, autoplay = true, known = hashSetOf("tap_pause", "tap_play", "bars", "strip_tap", "strip_scroll"))
        val n = done.host.hints.size
        done.floor.touched()
        assertEquals(n, done.host.hints.size, "no hint, nothing to tell")
    }

    /** a screen that keeps no flags (the default) shows no hints at all */
    @Test fun aScreenThatKeepsNothingShowsNoHints() {
        val r = rig(exercise, autoplay = true)
        r.run(3000.0); r.floor.tap(); r.floor.swipeDown(); r.floor.tapStop(3)
        assertTrue(r.host.hints.all { it == null }, "${r.host.hints}")
    }

    /** user 2026-11-21: "swiping down when the bars are up ... should also close them. Like swiping up or down opens
     *  them and swiping up or down closes them" */
    @Test fun aSwipeUpOrDownBringsTheBarsAndASwipeUpOrDownSendsThemAway() {
        for (first in listOf(true, false)) for (second in listOf(true, false)) {
            val known = HashSet<String>()
            val r = rig(exercise, known = known)
            fun swipe(down: Boolean) { if (down) r.floor.swipeDown() else r.floor.swipeUp() }
            fun way(down: Boolean) = if (down) "down" else "up"
            assertFalse(r.host.bars)
            swipe(first)
            assertTrue(r.host.bars && r.floor.barsShown, "a swipe ${way(first)} brings the bars")
            assertEquals(setOf("bars"), known, "and the user has found them, whichever way the swipe went")
            swipe(second)
            assertFalse(r.host.bars || r.floor.barsShown, "a swipe ${way(second)} sends them away")
            swipe(!second)
            assertTrue(r.host.bars, "and a swipe ${way(!second)} brings them again")
            // the same while the dance plays
            r.floor.tap()
            assertTrue(r.floor.playing && !r.host.bars, "playing closes the bars")
            r.run(1500.0)
            swipe(second); assertTrue(r.host.bars, "while it plays: a swipe ${way(second)} brings the bars")
            swipe(first); assertFalse(r.host.bars, "and a swipe ${way(first)} sends them away")
            assertTrue(r.floor.playing, "the dance plays on")
        }
    }

    @Test fun aTapPlaysAndATapPauses() {
        val r = rig(exercise)
        assertFalse(r.host.bars, "opened standing, the bars away")
        r.floor.swipeDown()
        assertTrue(r.host.bars, "a swipe down brings them")
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
        assertFalse(r.host.bars, "a swipe does")
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
