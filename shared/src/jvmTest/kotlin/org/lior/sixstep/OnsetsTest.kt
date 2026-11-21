package org.lior.sixstep

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recognising a song among the listener's own files (Onsets): on sounds made here, which need no music file; and,
 *  when tools/check_songs.py has written its fixture, on the bundled songs against the Python tool that makes the
 *  shipped curves (music/tools/onset_curve.py). */
class OnsetsTest {
    private val fs = Onsets.FS

    /** a steady stream of numbers that is the same on every run */
    private class Dice(seed: Long) {
        private var s = seed
        fun next(): Double { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) }
    }

    /** `seconds` of something like music: short bursts of noise, about two a second at uneven times, over a quiet hiss */
    private fun music(seed: Long, seconds: Double): FloatArray {
        val dice = Dice(seed)
        val out = FloatArray((seconds * fs).toInt()) { ((dice.next() - 0.5) * 0.004).toFloat() }
        var t = 0.3
        while (t < seconds - 0.2) {
            val start = (t * fs).toInt(); val loud = 0.3 + 0.6 * dice.next(); val decay = 0.02 + 0.05 * dice.next()
            var i = 0
            while (i < fs / 4 && start + i < out.size) {
                out[start + i] += (loud * (dice.next() - 0.5) * 2 * exp(-i / (decay * fs))).toFloat()
                i++
            }
            t += 0.25 + 0.5 * dice.next()
        }
        return out
    }

    /** a copy as a listener might have it: starting later (or earlier), quieter, with a little noise of its own */
    private fun copy(of: FloatArray, shift: Double, gain: Double, seed: Long): FloatArray {
        val dice = Dice(seed)
        val by = (shift * fs).roundToInt()
        return FloatArray(of.size + by) { i -> val j = i - by; ((if (j in of.indices) of[j] * gain else 0.0) + (dice.next() - 0.5) * 0.002).toFloat() }
    }

    /** a curve as it is shipped: 0..255, 255 for the value that 99.5 % of the frames stay under */
    private fun shipped(c: OnsetCurve): OnsetCurve {
        val top = c.clipped().values.max()
        return OnsetCurve(c.rate, c.t0, FloatArray(c.values.size) { (c.values[it] / top * 255).roundToInt().coerceIn(0, 255).toFloat() })
    }

    @Test fun aCopyIsRecognisedAndItsStartFoundAndAnotherSongIsNot() {
        val song = music(1, 110.0)
        val known = KnownSong("a", "A", "", 120.0, 110.0, "", "", "")
        val other = KnownSong("b", "B", "", 120.0, 110.0, "", "", "")
        val book = listOf(Pair(known, shipped(Onsets.curve(song))), Pair(other, shipped(Onsets.curve(music(2, 110.0)))))
        assertEquals(100.0, book[0].second.rate, 1e-9)
        assertEquals(1024.0 / 48000, Onsets.curve(song).t0, 1e-12)

        for (shift in listOf(0.0, 0.7337, 3.2001, -0.4013)) {
            val mine = Onsets.curve(copy(song, shift, 0.5, 7))
            val fit = Onsets.fit(book[0].second, mine.clipped())
            assertTrue(fit.good, "a copy that starts $shift s later is the song: score ${fit.score}, whole ${fit.whole}, cover ${fit.cover}")
            assertTrue(fit.score > 0.8, "score ${fit.score}")
            assertTrue(abs(fit.shift - shift) < 0.003, "the start is found to within 3 ms: ${fit.shift} for $shift")
            val found = Onsets.recognise(mine, book)
            assertNotNull(found); assertEquals("a", found.first.key)
        }
        // another song is none of the two
        val stranger = Onsets.curve(music(3, 110.0))
        assertNull(Onsets.recognise(stranger, book), "a song that is not known is not taken for one that is")
        assertTrue(Onsets.fit(book[0].second, stranger.clipped()).score < 0.3)
        // the same recording cut short is not the song: its beat map would run on after the music has stopped
        val short = Onsets.curve(song.copyOf(50 * fs))
        val cut = Onsets.fit(book[0].second, short.clipped())
        assertTrue(cut.score > 0.8, "the beginning fits: ${cut.score}")
        assertFalse(cut.good, "but only ${cut.cover} of the song is there")
        assertNull(Onsets.recognise(short, book))
        assertEquals("a", Onsets.soundsLike(Onsets.fits(short, book))?.key, "it sounds like the song without being the mapped recording")
        assertNull(Onsets.soundsLike(Onsets.fits(stranger, book)), "another song sounds like none")
    }

    @Test fun theCurveCanBeMadeAFewFramesAtATime() {
        val sound = music(5, 30.0)
        val whole = Onsets.curve(sound)
        val meter = Onsets.Meter(sound)
        var steps = 0
        while (!meter.step(250)) steps++
        assertTrue(steps > 5)
        assertEquals(meter.frames, meter.done)
        val slow = meter.curve()
        assertEquals(whole.values.size, slow.values.size)
        for (i in whole.values.indices) assertEquals(whole.values[i], slow.values[i], "frame $i")
        assertEquals(1 + (sound.size - Onsets.N) / 480, whole.values.size)
    }

    @Test fun aBeatGridIsMovedOntoACopy() {
        val g = BeatGrid(120.0, 0.5, 0.67, 100.0, DoubleArray(200) { 0.2 + it * 0.5 }, DoubleArray(50) { 0.2 + it * 2.0 })
        val s = g.shifted(0.7337, 101.0)
        assertEquals(101.0, s.duration)
        assertEquals(g.beats[10] + 0.7337, s.beats[10], 1e-12)
        assertEquals(g.phase, s.phase)
        assertEquals(g.beatAt(10.0), s.beatAt(10.7337), 1e-9)
    }

    @Test fun theShippedSongsListAndTheirCurvesAreRead() {
        val assets = File("../app/src/main/assets")
        val songs = KnownSong.parseAll(File(assets, "dance/songs.json").readText())
        assertTrue(songs.isNotEmpty())
        val tracks = Track.parseAll(File(assets, "dance/tracks.json").readText())
        for (s in songs) {
            assertTrue(tracks.none { it.key == s.key }, "${s.key} is not also a bundled track")
            val curve = OnsetCurve.parse(File(assets, s.onsetsPath).readText())
            assertEquals(100.0, curve.rate, 1e-9, "${s.title}: a frame every 10 ms")
            assertTrue(abs(curve.seconds - s.duration) < 1.0, "${s.title}: the curve covers the song (${curve.seconds} s of ${s.duration} s)")
            val grid = BeatGrid.parse(File(assets, s.gridPath).readText())
            assertTrue(grid.beats.size > 100 && grid.bars.isNotEmpty(), "${s.title}: a beat grid with bars")
            assertTrue(abs(grid.bpm - s.bpm) < 0.1)
        }
    }

    /** With SIXSTEP_ONSETS_FIXTURE set (tools/check_songs.py): real songs as raw sound, the curve the Python tool makes
     *  of each, and copies of them with the shift each must show. Without it there is nothing to compare. */
    @Test fun theKotlinCurveIsThePythonToolsCurve() {
        val path = System.getenv("SIXSTEP_ONSETS_FIXTURE") ?: return
        val fx = Json.obj(File(path).readText())
        fun pcm(file: String): FloatArray {
            val bytes = File(file).readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buf.remaining()).also { buf.get(it) }
        }
        val songs = fx.arr("songs")
        val book = ArrayList<Pair<KnownSong, OnsetCurve>>()
        for (i in 0 until songs.size) {
            val s = songs.obj(i)
            val mine = Onsets.curve(pcm(s.str("pcm")))
            val theirs = s.arr("values")
            assertEquals(theirs.size, mine.values.size, "${s.str("name")}: as many frames")
            // the same numbers: after the same rounding to 0..255, never more than one step apart
            val scale = s.num("scale")
            var worst = 0.0
            for (k in mine.values.indices) {
                val q = (mine.values[k] / scale * 255).roundToInt().coerceIn(0, 255)
                worst = maxOf(worst, abs(q - theirs.num(k)))
            }
            assertTrue(worst <= 1.0, "${s.str("name")}: the Kotlin curve differs from the Python tool's by up to $worst of 255")
            book.add(Pair(KnownSong(s.str("name"), s.str("name"), "", 0.0, mine.seconds, "", "", ""),
                OnsetCurve(mine.rate, mine.t0, FloatArray(theirs.size) { theirs.num(it).toFloat() })))
        }
        val copies = fx.arr("copies")
        for (i in 0 until copies.size) {
            val c = copies.obj(i)
            val found = Onsets.recognise(Onsets.curve(pcm(c.str("pcm"))), book)
            val want = c.optStr("song", "")
            if (want.isEmpty()) { assertNull(found, "${c.str("label")} must not be taken for ${found?.first?.key}"); continue }
            assertNotNull(found, "${c.str("label")} is not recognised")
            assertEquals(want, found.first.key, c.str("label"))
            assertTrue(abs(found.second.shift - c.num("shift")) < 0.003, "${c.str("label")}: shift ${found.second.shift}, should be ${c.num("shift")}")
        }
        println("onsets: ${songs.size} songs, ${copies.size} copies compared with the Python tool")
    }
}
