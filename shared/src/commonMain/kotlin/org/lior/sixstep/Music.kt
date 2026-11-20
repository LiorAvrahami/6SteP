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
