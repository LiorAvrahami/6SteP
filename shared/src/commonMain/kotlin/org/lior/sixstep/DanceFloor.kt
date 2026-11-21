package org.lior.sixstep

import kotlin.math.floor
import kotlin.math.roundToInt

/** What the dance floor needs from the sound: the music laid along the dance, the drums, the voice. The Android app's
 *  Playback and the web front's player both are one. Beats are dance beats: 0 is the first count of the piece. */
interface FloorPlayer {
    val playing: Boolean
    val muted: Boolean
    /** a track's key, MusicPlan.ALL or MusicPlan.SHUFFLE */
    val song: String
    val bpmOffset: Int
    val tracks: List<Track>
    /** the user can add songs of their own here (FloorHost.addSong opens the window for it) */
    val canAddSongs: Boolean get() = false
    /** the music that was asked for is still being fetched or unpacked: the dance waits on the step it starts from */
    val loading: Boolean
    /** the dance beat now; until the music has really started, the beat it starts from */
    fun beatNow(): Double
    /** the tempo danced (BPM): what plays, or what would play first */
    fun bpm(): Double
    fun currentTrack(): Track?
    /** where the "&" falls in the track that plays (or would play first), or null when there is no track */
    fun swing(): Double?
    /** play from dance beat b, everything made afresh */
    fun play(b: Double)
    /** stop and forget everything that was playing */
    fun stop()
    fun setMuted(m: Boolean)
    fun chooseSong(key: String)
    fun setBpmOffset(off: Int)
    /** phrases that will be spoken, so that they can be made ready */
    fun prefetch(texts: Collection<String>)
    fun speak(text: String)
    /** where a track may start: the first element start at or after a dance beat; null after the end of the piece */
    var boundaryAfter: (Double) -> Double?
    /** the song or the tempo changed */
    var onChange: (() -> Unit)?
    /** something else took the sound (a call): the floor pauses */
    var onFocusLoss: (() -> Unit)?
    var onMessage: ((String) -> Unit)?
    /** the settings page may have changed since */
    fun readSettings()
    fun release()
}

/** What the dance floor needs from the screen it is shown on. */
interface FloorHost {
    /** a steady clock, in milliseconds */
    fun nowMs(): Double
    /** call back once, at the screen's next frame */
    fun nextFrame(callback: () -> Unit)
    fun show(scene: Scene)
    /** the bottom bar's pictures and its annotations (the stop each starts at, its words) */
    fun strip(cells: List<StepCell>, callouts: List<Pair<Int, String>>)
    /** the stop that is framed in the bottom bar; -1 for none */
    fun stripCurrent(k: Int)
    fun stripScrollTo(k: Int, animate: Boolean)
    /** keep stop k in the middle of the bottom bar while the dance plays (unless the user is handling the bar) */
    fun stripFollow(k: Int)
    /** slide the top and bottom bars in or out */
    fun bars(shown: Boolean)
    /** the play, mute, tempo and song buttons have something new to show */
    fun buttons()
    fun message(text: String)
    /** the window in which the user adds a song of their own (the song menu's "Upload…") */
    fun addSong() {}
}

/** The one dance floor (the user's layout draft, 2026-10-28): every element, building block and composition is danced
 *  here, the daily exercise included. This is all of it that does not depend on the screen: the fronts (the Android
 *  activity, the web page) pass on the gestures and draw what it shows.
 *
 *  Swipe left or right: the next or previous step. There is one way of stepping (user 2026-11-17): the feet travel to
 *  the step, and once they are there dashed arrows show the path each foot that stepped came by, with a small round
 *  arrow where a foot turns more than a quarter turn (both dancers at once). Swipe down: the top and bottom bars;
 *  swipe up closes them. A tap on the floor, or the play button: play when the dance stands, pause when it plays (user
 *  2026-11-21: "a single tap is enough" to pause, and "a single tap ... to continue playing the dance instead of a
 *  double tap"); play closes the bars. A swipe while playing jumps to the next part, or back to the start of this one
 *  (the previous one in its first 2 s).
 *  Bottom bar: every step of the piece, the current one framed, the annotations pointing at where they start; a tap on
 *  a step goes there. While a composition plays, each annotation (a "say:" text, the name of an entry) comes up at the
 *  top, and is read aloud, 2 beats before its element starts, and goes away just as the element starts. The voice says
 *  how many times an entry repeats; the screen never does.
 *  A swipe to a part of a composition that plays does not drop the dancers into the part: they first dance two rock
 *  triple triples on the spot where the part starts, in the position it starts from, and go from them into the part
 *  (user 2026-11-19: "if I go back or forwards, like during play ... then start by adding twice rock triple triple in
 *  place"; see LeadIn). The part's name comes up 2 beats before it, as always, and is not read aloud after a swipe.
 *  Compositions play once to the end; elements and building blocks loop (chained so that the last beat flows into the
 *  first when they close on themselves, otherwise from the start position again after two beats). */
class DanceFloor(
    private val data: DanceData, store: CompositionStore,
    kind: String, private val name: String, variant: Int, private val autoplay: Boolean,
    private val pb: FloorPlayer, private val host: FloorHost
) {
    companion object {
        const val ANNOUNCE_LEAD = 2.0         // an annotation comes up this many beats before its element starts, and goes as it starts (user 2026-11-16)
        const val MIN_NOTICE = 0.5            // with less time than this left before its element, it is not written at all (too short to read)
        val TIMES = Regex(",\\s*\\d+\\s+times\\s*$")   // how often an entry repeats, as the voice says it: never written on the screen
        const val BACK_REPLAY_SECONDS = 2.0   // swipe right in the first 2 s of a part goes to the previous one (as in Workout Instructinator)
        const val LOOP_REST = 2.0             // beats of the start position before a piece that does not close is danced again
        const val CHAIN_BEATS = 1200.0        // a piece that closes is chained to about this long for play
        // stepping by hand: the feet's travel to the next stop takes this long, in beats of the music at the chosen tempo,
        // whatever the step (user 2026-11-17: a travel as long as its step made the half steps fast and the whole steps
        // slow, which was mostly confusing)
        const val STEP_BEATS = 1.0 / 3.0
        const val UPLOAD = "upload"           // the song menu's line that adds a song: not a song
        const val LEAD_IN_TIMES = 2           // rock triple triples before a part that a swipe went to (user 2026-11-19: "twice")
        const val ROCK = "rock step"          // a rock triple triple: this, then Composer.FILLER (a triple step) twice
    }

    /** a line of the tempo or the song menu. `key`: what the line stands for (a song menu's); a line that is not
     *  `checkable` does something instead of being chosen (the song menu's "Upload…") */
    class Choice(val label: String, val checked: Boolean, val key: String = "", val checkable: Boolean = true)

    val piece: Piece? = Piece.find(data, store, kind, name, variant)
    /** why there is nothing to dance, when there is not */
    var error: String? = null
        private set

    private var tl: Timeline? = null              // the piece danced once: what the swipes and the strip go through
    private var playTl: Timeline? = null          // what plays: the piece itself, or chained into a long loop
    private var loopBeats = 0.0                   // one loop in playTl (the piece and the triple steps that close it); 0: not chained
    private var period = 0.0                      // a piece that does not close: danced again every `period` beats
    private var playStarts = DoubleArray(0)       // the element starts of playTl
    private var stops: List<Double> = emptyList()
    private var parts: List<Double> = emptyList() // where the swipes jump while playing, in beats of the piece
    private var idx = 0
    var playing = false
        private set
    private var hold = Double.NEGATIVE_INFINITY   // the dancers wait here until the clock reaches it (a count-in)
    private var pausedClock = Double.NaN          // the clock beat of the last pause: only which loop we are in
    var barsShown = false
        private set
    private var stripCurrent = -1
    private var cues: List<Pair<Double, CElement>> = emptyList()   // a composition's annotations: (its element's start, the element), in order
    private var lastCue = -1                             // the last annotation that has come up, or whose element is under way
    private var jumpLead = 0.0                           // a swipe lands this many beats before a part: where its annotation comes up (compositions)
    private var silentUntil = Double.NEGATIVE_INFINITY   // a jump's part (an element starting by this beat) is shown, not read aloud
    private var banner: String? = null
    private var bannerUntil = Double.NEGATIVE_INFINITY

    /** the feet under way from one stop to the next: the dance from beat `from` to beat `to`, shown from t0 over ms */
    private class Travel(val from: Double, val to: Double, val stop: Double, val t0: Double, val ms: Double)
    private var travel: Travel? = null
    /** the feet are on their way to a stop (after a swipe, not playing) */
    val travelling: Boolean get() = travel != null
    private var frameAsked = false
    private var released = false
    private var swing = Piece.SWING

    /** The dancers' way into a part that a swipe went to while a composition plays: `beats` beats of rock step, triple
     *  step, triple step (LEAD_IN_TIMES times; once when the part has only that much dance before it), danced on the
     *  spot where the part starts, in the position it starts from, flowing into the part's first step.
     *  `tl` is that little dance with the part's first element behind it, composed on its own from the part's start
     *  position; `frame` moves and turns it onto the place where the composition has the part, so that the picture
     *  goes from it into the composition without a jump (the two have the part's first element in common: its steps
     *  land on the same spots). The clock and the music simply run through the `beats` beats before the part. */
    private class LeadIn(val tl: Timeline, val frame: Frame, val beats: Double)

    /** a swipe's part being approached: part `part` starts at clock beat `end`; until then the dancers dance `lead`, or,
     *  where there is none (see leadIn), the composition's own last beats before the part */
    private class Approach(val part: Int, val end: Double, val lead: LeadIn?)
    private var approach: Approach? = null
    private val leads = HashMap<Int, LeadIn>()       // made when first needed, by part
    private val noLead = HashSet<Int>()              // parts that cannot have one

    init {
        pb.onChange = { host.buttons() }
        pb.onFocusLoss = { pause() }
        pb.onMessage = { m -> host.message(m) }
        val p = piece
        swing = pb.swing() ?: Piece.SWING
        val composed = p?.compose(data, swing)
        if (p == null || composed == null || composed.errors.isNotEmpty()) {
            error = composed?.errors?.joinToString("\n") ?: Texts.NOTHING_TO_SHOW
        } else {
            val t = Timeline(composed, swing); tl = t
            stops = stopsOf(t)
            val ptl: Timeline = if (p.isComposition) t else chainedLoop(p, swing) ?: t.also { period = t.length + LOOP_REST }
            playTl = ptl
            playStarts = ptl.composed.elements.map { it.start }.toDoubleArray()
            parts = (if (p.isComposition) composed.elements.filter { it.entryStart || it.annotation != null } else composed.elements)
                .map { it.start }.ifEmpty { composed.elements.map { it.start } }
            pb.boundaryAfter = { b -> boundaryAfter(b) }
            // a composition plays once, so the clock's beats are its own beats
            if (p.isComposition) cues = ptl.composed.elements.filter { said(it).isNotEmpty() }.map { Pair(it.start, it) }
            if (cues.isNotEmpty()) jumpLead = ANNOUNCE_LEAD
            if (p.isComposition) pb.prefetch(composed.elements.mapNotNull { announcement(it) }.distinct())
        }
    }

    /** the first picture, once the screen is there: the first step (the start position), playing at once for the daily
     *  exercise, otherwise with the bars shown */
    fun open() {
        val t = tl; val p = piece
        if (error != null || t == null || p == null) {
            host.show(Scene(emptyMap(), emptyMap(), name, banner = error ?: Texts.NOTHING_TO_SHOW))
            return
        }
        host.strip(cellsOf(t, stops, Texts.START), callouts(p, t))
        host.buttons()
        show()
        if (autoplay) play() else showBars()
    }

    // ------------------------------------------------------------------ what the fronts pass on

    /** a swipe to the left (forward = true) or to the right */
    fun swipe(forward: Boolean) { if (playing) skip(forward) else step(if (forward) 1 else -1) }
    fun swipeDown() = showBars()
    fun swipeUp() = hideBars()

    /** a tap on the floor, passed on the moment the finger lifts: it pauses the dance when it plays, and plays it when
     *  it stands (user 2026-11-21). There is no double tap any more: every tap is one of these. */
    fun tap() = playPause()
    fun playPause() { if (playing) pause() else play() }
    fun toggleMute() { pb.setMuted(!pb.muted); host.buttons() }
    /** the page went out of sight, or the app into the background */
    fun hidden() { if (playing) pause() }
    fun release() { released = true; travel = null; playing = false; pb.release() }

    val muted: Boolean get() = pb.muted
    val loading: Boolean get() = playing && pb.loading
    /** the tempo button's number */
    fun tempoLabel(): String = pb.bpm().roundToInt().toString()
    /** the song button's words: the song that plays, or would play first */
    fun songLabel(): String {
        val title = pb.currentTrack()?.title ?: ""
        return if (pb.song == MusicPlan.SHUFFLE) Texts.shuffleNow(title) else title
    }

    /** the tempo: the song's tempo variants, from 40 BPM slower to 20 BPM faster (the same offset for every song). A
     *  song that has only its own tempo (one that the listener added) has only that line. */
    fun tempoChoices(): List<Choice> {
        val t = pb.currentTrack() ?: return emptyList()
        val plays = t.variantFor(pb.bpmOffset).offset
        return MusicPlan.BPM_OFFSETS.filter { off -> t.variants.isEmpty() || t.variants.any { it.offset == off } }.map { off ->
            val bpm = t.variantFor(off).bpm.roundToInt()
            Choice(if (off == 0) Texts.bpmOriginal(bpm) else Texts.bpm(bpm), off == plays, off.toString())
        }
    }
    /** a line of tempoChoices() was picked */
    fun chooseTempo(key: String) { key.toIntOrNull()?.let { pb.setBpmOffset(it); host.buttons() } }

    /** the song menu (user 2026-11-21): first "Play all", which is what plays unless something else is chosen: the songs
     *  in the order they are stored, the first one first, round and round. Then each song by itself, in that order;
     *  where songs can be added, "Upload…"; and last "Shuffle play". Nothing says which song is the best: it is
     *  simply the first. */
    fun songChoices(): List<Choice> {
        val out = ArrayList<Choice>()
        val one = pb.tracks.any { it.key == pb.song }
        out.add(Choice(Texts.PLAY_ALL, !one && pb.song != MusicPlan.SHUFFLE, MusicPlan.ALL))
        for (t in pb.tracks) {
            val hint = t.difficulty.substringBefore(":").substringBefore(",").trim()
            val b = Texts.bpm(t.bpm.roundToInt())
            out.add(Choice(if (hint.isEmpty()) "${t.title} · $b" else "${t.title} · $b · $hint", pb.song == t.key, t.key))
        }
        if (pb.canAddSongs) out.add(Choice(Texts.UPLOAD, false, UPLOAD, checkable = false))
        out.add(Choice(Texts.SHUFFLE_PLAY, pb.song == MusicPlan.SHUFFLE, MusicPlan.SHUFFLE))
        return out
    }
    /** a line of songChoices() was picked */
    fun chooseSong(key: String) {
        if (key == UPLOAD) { host.addSong(); return }
        pb.chooseSong(key); host.buttons()
    }

    // ------------------------------------------------------------------ beats: the clock's, the playing timeline's, the piece's

    /** the length of one loop in clock beats; 0 for a composition, which plays once */
    private fun cycle(): Double = if (loopBeats > 0) loopBeats else period

    /** the beat of playTl shown at clock beat b */
    private fun playBeat(b: Double): Double {
        val ptl = playTl ?: return b
        return when {
            b < 0 -> b
            loopBeats > 0 -> b % ptl.length
            period > 0 -> { val m = b % period; if (m < ptl.length) m else m - period }
            else -> b
        }
    }

    /** the beat of the piece (danced once) at clock beat b; NaN in the triple steps that close a loop */
    private fun pieceBeat(b: Double): Double {
        val t = tl ?: return b
        if (b < 0 || cycle() <= 0) return b
        if (loopBeats > 0) { val x = b % loopBeats; return if (x < t.length) x else Double.NaN }
        return playBeat(b)
    }

    /** the clock beat of beat p of the piece, in the loop that clock beat `near` is in (the first when there is none) */
    private fun clockBeat(p: Double, near: Double): Double {
        val c = cycle()
        if (c <= 0 || near.isNaN() || near < 0) return p
        return floor(near / c) * c + p
    }

    /** where a new track may start: the first element start at or after clock beat b; null after a composition's end */
    private fun boundaryAfter(b: Double): Double? {
        val ptl = playTl ?: return null
        val wrap = if (loopBeats > 0) ptl.length else period
        if (wrap <= 0) return playStarts.firstOrNull { it >= b }
        val base = if (b < 0) 0.0 else floor(b / wrap) * wrap
        for (k in 0..1) for (s in playStarts) { val x = base + k * wrap + s; if (x >= b) return x }
        return null
    }

    /** the starts of the parts in clock beats, around clock beat b */
    private fun partsNear(b: Double): List<Double> {
        val c = cycle()
        if (c <= 0) return parts
        val base = if (b < 0) 0.0 else floor(b / c) * c
        return listOf(base - c, base, base + c).filter { it >= 0 }.flatMap { s -> parts.map { s + it } }
    }

    /** the piece padded with triple steps until it closes, then chained to about CHAIN_BEATS: the loop that plays, so
     *  that the last beat flows into the first. Null when the piece does not close on itself. */
    private fun chainedLoop(p: Piece, swing: Double): Timeline? {
        val one = p.compose(data, swing, padToLoop = true)
        if (one.errors.isNotEmpty() || !one.closes) return null
        val names = one.elements.map { SeqItem(it.name, it.group, null, it.entryIndex, it.entryStart, it.entryLabel, it.endState) }   // the versions the first loop chose
        val n = (CHAIN_BEATS / one.length).toInt().coerceIn(2, 600)
        val chain = ArrayList<SeqItem>(); repeat(n) { chain.addAll(names) }
        val c = Composer.compose(data, chain, p.startState, swing, p.free)
        if (c.errors.isNotEmpty()) return null
        val m = one.elements.size
        val elements = c.elements.mapIndexed { k, e -> e.copy(filler = one.elements[k % m].filler) }
        val steps = c.steps.map { s -> s.copy(filler = elements[s.element].filler) }
        loopBeats = one.length
        return Timeline(Composed(steps, elements, c.start, c.startHands, c.startState, c.startFree, c.endState, c.endFree, c.length, c.errors), swing)
    }

    // ------------------------------------------------------------------ what the floor shows

    private fun weightsAt(t: Timeline, b: Double) = mapOf('L' to t.weights('L', b), 'F' to t.weights('F', b))
    private fun fillerColor(el: CElement?): Int? = if (el?.filler == true) Colors.FILLER else null

    private fun titleOf(el: CElement?, b: Double, counting: Boolean): String = when {
        b < 0 -> if (counting) Texts.READY else Texts.START_POSITION
        el == null -> ""
        el.filler -> el.name + Texts.FILLER_SUFFIX
        el.group != null -> el.name + "   (" + el.group + ")"
        else -> el.name
    }

    /** the instant a stop is shown at, and play starts from: the weight settled on what landed at b, but before anything
     *  lands next; one instant for the feet and the weights, so that the first frame of play is this very picture */
    private fun settled(t: Timeline, b: Double): Double {
        var next = Double.POSITIVE_INFINITY
        for (d in t.dancers.values) for (track in d.tracks.values) { val n = track.around(b).second; if (n != null && n.tLand > b + 1e-6) next = minOf(next, n.tLand) }
        return minOf(t.settledAfter(b), next - 1e-3)
    }

    /** a stop at rest: the feet and their weights at its settled instant, and a dashed arrow for every foot that
     *  landed at the stop, from where it was and along the path it took (both dancers at once); the floor adds a small
     *  round arrow beside a step that turns its foot by more than a quarter turn (FeetPainter.arrows) */
    private fun stopScene(t: Timeline, b: Double, banner: String?): Scene {
        val arrows = ArrayList<Arrow>()
        for (d in listOf('L', 'F')) for (f in listOf('L', 'R')) {
            val track = t.dancers[d]!!.tracks[f]!!
            val (cur, _) = track.around(b)
            if (kotlin.math.abs(cur.tLand - b) < 1e-6) {
                val i = track.landings.indexOf(cur)
                if (i > 0) {
                    val prev = track.landings[i - 1].p
                    // a foot that moved, went round by the anchors of its path, or turned a lot on the spot
                    val moved = kotlin.math.abs(prev.x - cur.p.x) > 0.5 || kotlin.math.abs(prev.y - cur.p.y) > 0.5
                    if (moved || cur.via.isNotEmpty() || kotlin.math.abs(cur.p.h - prev.h) > FeetPainter.TURN_MARK) arrows.add(Arrow(d, prev, cur.p, cur.via))
                }
            }
        }
        val el = t.elementAt(b)
        val s = settled(t, b)
        return Scene(t.feetAt(s), weightsAt(t, s), titleOf(el, b, false), if (b < 0) "" else t.countLabel(b), arrows, banner, fillerColor(el))
    }

    /** any instant of the dance as it moves: while it plays, and while the feet travel to a stop; no arrows */
    private fun flowScene(t: Timeline, b: Double, banner: String?, counting: Boolean): Scene {
        val el = t.elementAt(b)
        return Scene(t.feetAt(b), weightsAt(t, b), titleOf(el, b, counting), if (b < 0) "" else t.countLabel(b), emptyList(), banner, fillerColor(el))
    }

    /** stop k as a beat of the clock: in the loop of the last pause (the first loop before any) */
    private fun stopBeat(k: Int): Double = clockBeat(stops[k], pausedClock)

    private fun setCurrent(k: Int) { stripCurrent = k; host.stripCurrent(k) }

    /** the stop we are on, drawn from the timeline that plays, in the loop of the last pause, so that play starts from
     *  exactly this picture */
    private fun show(banner: String? = null) {
        val ptl = playTl ?: return
        host.show(stopScene(ptl, playBeat(stopBeat(idx)), banner))
        setCurrent(idx)
        if (barsShown) host.stripScrollTo(idx, true)
    }

    /** an entry as the voice says it, with how often it repeats: "side to side x10" -> "side to side, 10 times" */
    private fun spokenEntry(label: String): String {
        val m = Regex("^(.*?)\\s*x(\\d+)$").matchEntire(label)
        return if (m != null) "${m.groupValues[1]}, ${m.groupValues[2]} times" else label
    }
    /** an element's annotation in a composition as the voice says it: its "say:" text, and the entry it starts */
    private fun said(e: CElement): List<String> = listOfNotNull(e.annotation, if (e.entryStart) spokenEntry(e.entryLabel) else null)
    /** the same as it is written, on the floor and in the bottom bar: without how many times, be it an entry's "x N" or a
     *  "say:" text that ends in ", N times" (user 2026-11-16: the voice says the number, the screen does not) */
    private fun phrases(e: CElement): List<String> = said(e).map { it.replace(TIMES, "") }
    private fun announcement(e: CElement): String? = said(e).joinToString(". ").ifEmpty { null }

    /** the bottom bar's annotations: a composition's "say:" texts and entry names; a building block's element names */
    private fun callouts(p: Piece, t: Timeline): List<Pair<Int, String>> = t.composed.elements.mapNotNull { e ->
        val text = when (p.kind) {
            Piece.COMPOSITION -> phrases(e).joinToString(" · ")
            Piece.BLOCK -> e.name
            else -> ""
        }
        if (text.isEmpty()) null else Pair(stopIndex(stops, e.start), text)
    }

    // ------------------------------------------------------------------ frames: the dance playing, the feet travelling

    private fun askFrame() {
        if (frameAsked || released) return
        frameAsked = true
        host.nextFrame { frameAsked = false; frame() }
    }

    private fun frame() {
        if (released) return
        val ptl = playTl ?: return
        if (playing) {
            val b = pb.beatNow()
            if (cycle() <= 0 && b >= ptl.length) { endOfPiece(); return }
            if (b >= hold) hold = Double.NEGATIVE_INFINITY
            approach?.let { if (b >= it.end) approach = null }   // the part has started
            announce(b)
            drawPlaying(if (b < hold) hold else b)
            askFrame()
            return
        }
        val tr = travel ?: return
        val u = ((host.nowMs() - tr.t0) / tr.ms).coerceIn(0.0, 1.0)
        if (u >= 1.0) { travel = null; host.show(stopScene(ptl, tr.stop, null)) }   // the feet are there: now the arrows
        else { host.show(flowScene(ptl, tr.from + (tr.to - tr.from) * u, null, false)); askFrame() }
    }

    // ------------------------------------------------------------------ stepping by hand

    /** one stop forward or back; past the last comes the first again, and before the first the last (user 2026-10-19).
     *  The one way of stepping (user 2026-11-17): the arrows of the stop we leave go, the feet travel from its settled
     *  state to the settled state of the next one, and only when they are there do that stop's arrows come (stopScene):
     *  where each foot that stepped came from, and by which path. Every travel takes STEP_BEATS of a beat, whatever the
     *  step, and plays that stretch of the dance at an even rate, so the feet speed up and slow down exactly as they do
     *  when the dance plays (Timeline: a foot eases out of its place and into its landing). A swipe back is the same
     *  travel backwards, to the picture of the stop before, with its own arrows. Going round the end is a cut. Another
     *  swipe, a tap on a step or play cut a travel short: they draw their own picture. */
    private fun step(dir: Int) {
        val ptl = playTl ?: return
        if (stops.size < 2) return
        var next = idx + dir
        val wrapped = next < 0 || next >= stops.size
        if (wrapped) next = if (next < 0) stops.size - 1 else 0
        travel = null
        val from = playBeat(stopBeat(idx)); val to = playBeat(stopBeat(next)); idx = next
        if (wrapped) { show(); return }
        setCurrent(idx)
        if (barsShown) host.stripScrollTo(idx, true)
        val tFrom = settled(ptl, from); val tTo = settled(ptl, to)
        val ms = (STEP_BEATS * 60.0 / pb.bpm() * 1000.0).coerceIn(60.0, 600.0)   // the limits only guard against a tempo that makes no sense
        travel = Travel(tFrom, tTo, to, host.nowMs(), ms)
        host.show(flowScene(ptl, tFrom, null, false))   // the arrows of the stop we leave go at once
        askFrame()
    }

    /** a tap on a step in the bottom bar goes straight there: the stop at rest, with its arrows */
    fun tapStop(k: Int) {
        if (k !in stops.indices) return
        if (playing) startAt(k, pb.beatNow(), quiet = true)
        else { travel = null; idx = k; show() }
    }

    // ------------------------------------------------------------------ playing: everything made afresh from a step

    private fun play() {
        if (playing || tl == null) return
        if (cycle() <= 0 && idx >= stops.size - 1) idx = 0   // a composition at its end starts again
        startAt(idx, pausedClock, quiet = false)
    }

    /** play from stop k, in the loop of clock beat `near`, with nothing kept from before (user 2026-10-28): the music
     *  from that step's place in the plan, the dance from exactly the picture of the step, the weight settled on what
     *  landed there. From the start position (unmuted), a count-in of four beats of music first, the dancers waiting.
     *  quiet: a jump while playing, whose part is shown but not read aloud (so that the music does not dip). */
    private fun startAt(k: Int, near: Double, quiet: Boolean) {
        val ptl = playTl ?: return
        travel = null; approach = null
        val b = clockBeat(stops[k], near)
        val countIn = k == 0 && !pb.muted
        val from = if (countIn) b - (MusicPlan.COUNT_IN - Piece.LEAD_IN) else b + (settled(ptl, playBeat(b)) - playBeat(b))
        pb.play(from)
        if (!pb.playing) { standAt(from); return }   // the sound is busy (a call)
        playing = true
        hold = if (countIn) b else Double.NEGATIVE_INFINITY
        lastCue = cues.indexOfLast { it.first < b - 0.01 }   // an element starting on this step is announced, one under way is not
        silentUntil = if (quiet) b + 0.05 else Double.NEGATIVE_INFINITY
        banner = null
        hideBars(); host.buttons()
        askFrame()
    }

    /** pause: everything that played is let go (players, clock, drums, voice); what stays is the step we are on */
    private fun pause() {
        if (!playing) return
        val b = pb.beatNow()
        pb.stop()
        // in the rock triple triples before a part: they are not steps of the piece, so the dance stands at the part's start
        val lead = approach?.takeIf { it.lead != null && b < it.end }
        standAt(if (lead != null) lead.end else if (b < hold) hold else b)
    }

    /** not playing, standing on the stop at clock beat b (in the triple steps that close a loop: its start) */
    private fun standAt(b: Double) {
        playing = false; approach = null
        pausedClock = b
        val p = pieceBeat(b)
        idx = if (p.isNaN()) 0 else stopIndex(stops, p)
        banner = null
        show(); host.buttons()
    }

    /** a composition danced to its end */
    private fun endOfPiece() {
        playing = false; approach = null; pb.stop(); pausedClock = Double.NaN
        idx = stops.size - 1
        show(Texts.DONE); host.buttons(); showBars()
    }

    /** while playing: to the next part, or back to the start of this one (the previous one in its first seconds) */
    private fun skip(forward: Boolean) {
        val b = pb.beatNow()
        if (cycle() > 0) { skipInLoop(forward, b); return }
        // a composition: its parts, one by one. While the dancers are still on their way into a part (the rock triple
        // triples before it), that part is the one we are in: forward goes to the one after it, back to the one before.
        val coming = approach?.takeIf { b < it.end }
        val cur = coming?.part ?: parts.indexOfLast { it <= b + 0.05 }
        val to = when {
            forward -> cur + 1
            coming != null -> cur - 1
            cur >= 0 && b - parts[cur] > BACK_REPLAY_SECONDS * pb.bpm() / 60.0 -> cur   // well into the part: its start again
            else -> cur - 1
        }
        if (to >= parts.size) return   // nothing after the last part
        jumpTo(to)
    }

    /** an element or a building block, which loops: a swipe goes straight to its next or previous element */
    private fun skipInLoop(forward: Boolean, b: Double) {
        val near = partsNear(b)
        val target = if (forward) (near.firstOrNull { it > b + 0.05 } ?: return) else {
            val cur = near.lastOrNull { it <= b + 0.05 } ?: 0.0
            if (b - cur > BACK_REPLAY_SECONDS * pb.bpm() / 60.0) cur else (near.lastOrNull { it < cur - 0.05 } ?: 0.0)
        }
        approach = null
        pb.play(target)   // from scratch, as any start
        if (!pb.playing) { standAt(target); return }
        hold = Double.NEGATIVE_INFINITY; banner = null
    }

    /** Go to part `i` of the composition that plays (-1: its very beginning). The dancers get there by the part's
     *  lead-in (LeadIn): the clock, and the music with it, starts as many beats before the part as the lead-in is long.
     *  A part that cannot have one (too near the beginning, or starting from a position there is no rock triple triple
     *  for) is entered as before 2026-11-21: ANNOUNCE_LEAD beats before it, in the composition's own last steps before
     *  it. Either way the part's annotation is on the screen for the last ANNOUNCE_LEAD beats and gone when the part
     *  starts (user 2026-11-16), and is not read aloud (2026-10-28: the music does not dip after a swipe). */
    private fun jumpTo(i: Int) {
        val start = if (i < 0) 0.0 else parts[i]
        val lead = if (i < 0) null else leadIn(i)
        val target = when { i < 0 -> 0.0; lead != null -> start - lead.beats; else -> start - jumpLead }
        pb.play(target)   // from scratch, as any start
        if (!pb.playing) { standAt(if (lead != null) start else target); return }   // the sound is busy (a call)
        approach = if (i < 0) null else Approach(i, start, lead)
        hold = Double.NEGATIVE_INFINITY; banner = null
        // the annotations of what the rock triple triples stand in for are not shown: the next one is the part's own
        lastCue = cues.indexOfLast { it.first < (if (lead != null) start else target) - 0.01 }
        silentUntil = (if (i < 0) jumpLead else start) + 0.05   // the part the swipe went to is written, not read aloud
    }

    /** the lead-in of part i, made when it is first needed; null when the part cannot have one */
    private fun leadIn(i: Int): LeadIn? {
        leads[i]?.let { return it }
        if (i in noLead) return null
        val made = makeLeadIn(i)
        if (made != null) leads[i] = made else noLead.add(i)
        return made
    }

    private fun makeLeadIn(i: Int): LeadIn? {
        val main = playTl ?: return null
        val start = parts[i]
        val k = main.composed.elements.indexOfFirst { kotlin.math.abs(it.start - start) < 1e-6 }
        if (k < 0) return null
        val e = main.composed.elements[k]
        // a rock triple triple that starts and ends in the part's start position, on the leader's free foot of that moment
        fun item(name: String, end: String) = SeqItem(name, null, null, -1, false, name, end)
        val rtt = listOf(item(ROCK, e.startState), item(Composer.FILLER, e.startState), item(Composer.FILLER, e.startState))
        val one = Composer.compose(data, rtt, e.startState, swing, e.free)
        if (one.errors.isNotEmpty() || one.length <= 0 || one.endState != e.startState || one.endFree != e.free) return null
        // as many of them as there is dance before the part, LEAD_IN_TIMES at most: the music has nothing before the beginning
        val times = minOf(LEAD_IN_TIMES, floor((start + 1e-6) / one.length).toInt())
        if (times < 1) return null
        val seq = ArrayList<SeqItem>()
        repeat(times) { seq.addAll(rtt) }
        seq.add(item(e.name, e.endState))   // the part's first element, the version the composition dances
        val c = Composer.compose(data, seq, e.startState, swing, e.free)
        if (c.errors.isNotEmpty()) return null
        // where the composition has that element, against where this little dance has it: one move and one turn
        val mine = c.steps.filter { it.element == seq.size - 1 }
        val theirs = main.composed.steps.filter { it.element == k }
        if (mine.isEmpty() || mine.size != theirs.size) return null
        val h = theirs[0].heading - mine[0].heading
        val turned = Frame(0.0, 0.0, h).apply(mine[0].x, mine[0].y, 0.0)
        val frame = Frame(theirs[0].x - turned.x, theirs[0].y - turned.y, h)
        for (n in mine.indices) {
            val q = frame.apply(mine[n].x, mine[n].y, mine[n].heading)
            if (kotlin.math.hypot(q.x - theirs[n].x, q.y - theirs[n].y) > 0.05) return null   // not the same steps: no lead-in
        }
        // The dancers must end the lead-in standing exactly as the composition has them before the part (what was danced
        // before it may have left a foot some centimetres from where a rock triple triple leaves it: up to 8.5 cm in
        // the daily exercise). So each foot's last step of the lead-in lands where the composition has that foot: it
        // travels there through the air as any step does, and nothing slides or jumps when the composition takes over.
        val steps = c.steps.toMutableList()
        for (d in listOf('L', 'F')) for (f in listOf('L', 'R')) {
            val n = steps.indexOfLast { it.dancer == d && it.foot == f && it.element < seq.size - 1 }
            if (n < 0) continue
            val at = main.dancers[d]!!.tracks[f]!!.around(start - 1e-6).first.p
            val local = Frame(0.0, 0.0, -frame.h).apply(at.x - frame.x, at.y - frame.y, 0.0)
            var turn = (at.h - frame.h - steps[n].heading) % 360.0   // the shortest way round to the heading it has there
            if (turn > 180.0) turn -= 360.0
            if (turn < -180.0) turn += 360.0
            steps[n] = steps[n].copy(x = local.x, y = local.y, heading = steps[n].heading + turn)
        }
        val fitted = Composed(steps, c.elements, c.start, c.startHands, c.startState, c.startFree, c.endState, c.endFree, c.length, c.errors)
        return LeadIn(Timeline(fitted, swing), frame, times * one.length)
    }

    /** the picture while the dancers dance a part's lead-in, at clock beat b: no step of the piece is framed below */
    private fun leadScene(a: Approach, lead: LeadIn, b: Double): Scene {
        val u = b - (a.end - lead.beats)   // the beat of the little dance
        val el = lead.tl.elementAt(u)
        val feet = LinkedHashMap<String, Placement>()
        for ((key, p) in lead.tl.feetAt(u)) feet[key] = lead.frame.apply(p)
        return Scene(feet, weightsAt(lead.tl, u), (el?.name ?: "") + Texts.LEAD_IN_SUFFIX, if (u < 0) "" else lead.tl.countLabel(u), emptyList(), banner, Colors.FILLER)
    }

    /** a composition's annotations, by the clock itself (so the first can come up during the count-in): each comes up
     *  ANNOUNCE_LEAD beats before its element starts, is read aloud, and goes away just as the element starts (user
     *  2026-11-16): once a part has started nothing is written. After a start or a jump, one whose time has already come
     *  comes up at once, for what is left until its element; with less than MIN_NOTICE beats left (play started on the
     *  part's first step) it is only read aloud. The part a jump went to is written, not read. */
    private fun announce(b: Double) {
        val next = cues.getOrNull(lastCue + 1)
        if (next != null && b >= next.first - ANNOUNCE_LEAD) {
            lastCue++
            if (next.first - b >= MIN_NOTICE) { banner = phrases(next.second).joinToString("\n"); bannerUntil = next.first }
            if (next.first > silentUntil) announcement(next.second)?.let { pb.speak(it) }
        }
        if (b > bannerUntil) banner = null
    }

    private fun drawPlaying(b: Double) {
        val ptl = playTl ?: return
        val a = approach
        val lead = a?.lead
        if (a != null && lead != null && b < a.end) { host.show(leadScene(a, lead, b)); setCurrent(-1); return }
        val x = playBeat(b)
        host.show(flowScene(ptl, x, banner, true))
        val p = pieceBeat(b)
        val k = if (p.isNaN()) -1 else stopIndex(stops, p)
        setCurrent(k)
        if (barsShown && k >= 0) host.stripFollow(k)
    }

    // ------------------------------------------------------------------ the bars

    private fun showBars() {
        if (barsShown) return
        barsShown = true
        host.stripScrollTo(maxOf(stripCurrent, 0), false)
        host.bars(true)
    }

    private fun hideBars() {
        if (!barsShown) return
        barsShown = false
        host.bars(false)
    }
}
