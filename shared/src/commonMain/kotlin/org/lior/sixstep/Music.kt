package org.lior.sixstep

import kotlin.math.abs
import kotlin.math.floor

/** The beat grid of a track: when every count happens. */
class BeatGrid(val bpm: Double, val beat: Double, val swing: Double, val duration: Double, val beats: DoubleArray, val bars: DoubleArray) {
    /** which beat index (mod 4) is the 1 of the bar; from the analysis, or from the user's taps (Sync music) */
    var phase: Int = if (bars.isEmpty() || beats.isEmpty()) 0 else { var k = 0; for (i in beats.indices) if (abs(beats[i] - bars[0]) < abs(beats[k] - bars[0])) k = i; k % 4 }
    /** seconds: the beats as heard sit this much after the measured ones (output latency, analysis bias); from the taps */
    var offset: Double = 0.0
    fun beatSeconds(b: Double): Double {
        val k = floor(b).toInt(); val frac = b - k
        if (k >= 0 && k + 1 < beats.size) return beats[k] + frac * (beats[k + 1] - beats[k])
        if (k < 0) return beats[0] + b * beat
        return beats.last() + (b - (beats.size - 1)) * beat
    }
    fun beatAt(s: Double): Double {
        if (beats.size < 2) return s / beat
        var j = 0
        while (j + 1 < beats.size - 1 && beats[j + 1] <= s) j++
        return j + (s - beats[j]) / (beats[j + 1] - beats[j])
    }
    /** the beat index where the dance starts: the first 1 of a bar at or after 3 s */
    fun startBeatIndex(): Int {
        for (i in beats.indices) if (beats[i] >= 3.0 && ((i - phase) % 4 + 4) % 4 == 0) return i
        return 0
    }
    /** the same grid for the music time-stretched by `tempo` (1.2 = a fifth faster): every beat lands at beats[i] / tempo.
     *  The latency offset is in real seconds and stays as it is. */
    fun scaled(tempo: Double): BeatGrid {
        val g = BeatGrid(bpm * tempo, beat / tempo, swing, duration / tempo, DoubleArray(beats.size) { beats[it] / tempo }, DoubleArray(bars.size) { bars[it] / tempo })
        g.phase = phase; g.offset = offset
        return g
    }
    companion object {
        /** a grid from the text of its file (dance/beatgrids/<track>.json: the beats annotated by ear when that was done) */
        fun parse(text: String): BeatGrid {
            val o = Json.obj(text)
            val ba = o.arr("beats"); val beats = DoubleArray(ba.size) { ba.num(it) }
            val bb = o.optArr("bars"); val bars = if (bb == null) DoubleArray(0) else DoubleArray(bb.size) { bb.num(it) }
            return BeatGrid(o.num("bpm"), o.num("beat"), o.optNum("swing", 0.67), o.optNum("duration", beats.last()), beats, bars)
        }
    }
}

/** A track time-stretched by `tempo` with its pitch kept: `offset` BPM faster (or slower, when negative) than the original. */
class TrackVariant(val offset: Int, val bpm: Double, val tempo: Double, val file: String)

class Track(val key: String, val title: String, val artist: String, val licence: String, val bpm: Double, val duration: Double, val file: String, val gridPath: String, val difficulty: String = "", val variants: List<TrackVariant> = emptyList()) {
    /** the variant `offset` BPM away from the original, or the nearest one there is */
    fun variantFor(offset: Int): TrackVariant = variants.minByOrNull { abs(it.offset - offset) } ?: TrackVariant(0, bpm, 1.0, file)

    companion object {
        /** the tracks from the text of dance/tracks.json */
        fun parseAll(text: String): List<Track> {
            val a = Json.obj(text).arr("tracks")
            return (0 until a.size).map { i ->
                val t = a.obj(i)
                val bpm = t.num("bpm"); val file = t.str("file")
                // the tempo variants; older assets without them have just the track itself
                val variants = ArrayList<TrackVariant>()
                val va = t.optArr("variants")
                if (va != null) for (k in 0 until va.size) {
                    val v = va.obj(k); val off = v.int("offset")
                    variants.add(TrackVariant(off, v.optNum("bpm", bpm + off), v.optNum("tempo", (bpm + off) / bpm), v.optStr("file", file)))
                }
                if (variants.none { it.offset == 0 }) variants.add(TrackVariant(0, bpm, 1.0, file))
                variants.sortBy { it.offset }
                Track(t.str("key"), t.str("title"), t.str("artist"), t.str("licence"), bpm, t.num("duration"), file, t.str("grid"), t.optStr("difficulty", ""), variants)
            }
        }
    }
}

/** The music laid along the dance, for the song and the tempo chosen (user 2026-10-28: "for every step the app should
 *  know via a lookup which beat this is in the audio"): the first track's first downbeat (BeatGrid.startBeatIndex) on
 *  dance beat 0, with four beats of it before (the count-in); each next track on the first element start at least two
 *  beats after the previous file runs out, the snare and hi-hat in between. "Play all" (the default, user 2026-11-21)
 *  goes through the songs in the order they are stored, the first one first, and after the last one comes the first
 *  again; a chosen song plays over and over; "shuffle play" goes through all of them in an order drawn afresh. So every
 *  dance beat has one place in the audio (segmentAt).
 *  (The web front's player is built on this. The Android app's Playback has the same plan inside it, for now.) */
class MusicPlan(tracks: List<Track>, private val gridOf: (Track) -> BeatGrid) {
    companion object {
        val BPM_OFFSETS = intArrayOf(-40, -30, -20, -10, 0, 10, 20)
        const val ALL = "all"                // every song, in the order they are stored
        const val SHUFFLE = "shuffle"

        /** the tempo a dance starts at with these choices, before anything plays: the chosen song's; the first song's
         *  when all are played in their order; the songs' average when they are shuffled (nobody knows which is first) */
        fun startTempo(tracks: List<Track>, song: String, bpmOffset: Int): Double {
            val chosen = tracks.firstOrNull { it.key == song }
            return when {
                chosen != null -> chosen.variantFor(bpmOffset).bpm
                tracks.isEmpty() -> 120.0
                song == SHUFFLE -> tracks.map { it.variantFor(bpmOffset).bpm }.average()
                else -> tracks.first().variantFor(bpmOffset).bpm
            }
        }
        const val COUNT_IN = 4.0             // beats of the first track before the dance starts
        const val GAP = 2.0                  // a track starts on the first element start at least this many beats after the last one ran out
        const val END_LEAD = 0.25            // s: a track counts as over this long before its file ends
        const val DUCK_LEVEL = 0.6           // the music under a spoken annotation
    }

    /** a track's place in the dance: grid beat k0 of `grid` (the track at the session's tempo) falls on dance beat `from`;
     *  the music covers the dance from musicFrom (four beats earlier for the first track) to until (the file runs out) */
    class Segment(val track: Track, val file: String, val grid: BeatGrid, val k0: Int, val from: Double, val musicFrom: Double, val until: Double) {
        /** where dance beat b is in the file, in seconds */
        fun seconds(b: Double): Double = grid.beatSeconds(b - from + k0) + grid.offset
        /** the dance beat at `seconds` into the file */
        fun beatAt(seconds: Double): Double = grid.beatAt(seconds - grid.offset) - k0 + from
    }

    /** where a track may start: the first element start at or after a dance beat; null after the end of the piece */
    var boundaryAfter: (Double) -> Double? = { null }
        set(v) { field = v; replan() }
    /** the songs there are, in the order they are stored; one that the user adds comes last */
    var tracks: List<Track> = tracks
        set(v) { field = v; reorder() }
    /** a track's key, ALL or SHUFFLE */
    var song: String = ALL
        set(v) { field = v; reorder() }
    var bpmOffset = 0
        set(v) { field = v; replan() }
    var order: List<Track> = emptyList()
        private set
    private val plan = ArrayList<Segment>()
    private var planDone = false

    init { reorder() }

    /** the chosen song, over and over; or every song: in the order they are stored, or shuffled */
    fun reorder() {
        val chosen = tracks.firstOrNull { it.key == song }
        order = when {
            chosen != null -> listOf(chosen)
            song == SHUFFLE -> tracks.shuffled()
            else -> tracks
        }
        replan()
    }

    fun replan() { plan.clear(); planDone = false }

    /** the segment of the plan that dance beat b is in or after (b is in a gap when it is past its `until`); the plan is
     *  laid out as far as b needs */
    fun segmentAt(b: Double): Segment? {
        while (!planDone && order.isNotEmpty() && (plan.isEmpty() || plan.last().until <= b + 8)) {
            val first = plan.isEmpty()
            val from = if (first) 0.0 else boundaryAfter(plan.last().until + GAP)
            if (from == null) { planDone = true; break }
            val t = order[plan.size % order.size]
            val v = t.variantFor(bpmOffset)
            val g = gridOf(t).scaled(v.tempo)
            val k0 = g.startBeatIndex()
            val until = from + g.beatAt(g.duration - END_LEAD - g.offset) - k0
            plan.add(Segment(t, v.file, g, k0, from, if (first) from - COUNT_IN else from, until))
        }
        return plan.lastOrNull { it.musicFrom <= b }
    }

    /** the first track of the plan that starts after dance beat b, or null */
    fun nextAfter(b: Double): Segment? { segmentAt(b); return plan.firstOrNull { it.from > b } }

    fun tempoAt(b: Double): Double = segmentAt(b)?.grid?.bpm ?: firstTempo()
    /** the tempo of the first track at the chosen offset */
    fun firstTempo(): Double = order.firstOrNull()?.variantFor(bpmOffset)?.bpm ?: 120.0
    /** every music file a session with these choices could need first: the chosen tempo of each track in the order */
    fun files(): List<String> = order.map { it.variantFor(bpmOffset).file }
}

/** The drums: snare on the odd counts (1, 3, 5), closed hi-hat on their swung "&" and on the even counts (2, 4, 6). */
object DrumPattern {
    /** the next hit after dance beat b. Hits per two beats starting at an even beat e: e (snare), e + swing (hat), e + 1 (hat) */
    fun nextAfter(b: Double, swing: Double): Double {
        val e = floor(b / 2.0) * 2.0
        val cands = doubleArrayOf(e, e + swing, e + 1.0, e + 2.0, e + 2.0 + swing, e + 3.0)
        for (c in cands) if (c > b + 1e-9) return c
        return e + 4.0
    }
    fun isSnare(b: Double): Boolean { val r = b - floor(b / 2.0) * 2.0; return abs(r) < 1e-6 }
}
