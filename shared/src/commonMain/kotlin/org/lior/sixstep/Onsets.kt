package org.lior.sixstep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A song's onset curve: how hard the music "hits" every 10 ms (music/tools/onset_curve.py). It is the song's rhythm,
 *  not its audio. One value per frame; frame 0 is centred `t0` seconds into the song. */
class OnsetCurve(val rate: Double, val t0: Double, val values: FloatArray) {
    val seconds: Double get() = values.size / rate

    /** the curve as it is stored: nothing above the value that 99.5 % of the frames stay under */
    fun clipped(): OnsetCurve {
        if (values.isEmpty()) return this
        val sorted = values.copyOf().also { it.sort() }
        val top = sorted[((sorted.size - 1) * 0.995).toInt()]
        if (top <= 0f) return this
        return OnsetCurve(rate, t0, FloatArray(values.size) { min(values[it], top) })
    }

    companion object {
        /** a curve from the text of its file (dance/onsets/<song>.json) */
        fun parse(text: String): OnsetCurve {
            val o = Json.obj(text)
            val a = o.arr("values")
            return OnsetCurve(o.num("rate"), o.optNum("t0", 0.0), FloatArray(a.size) { a.num(it).toFloat() })
        }
    }
}

/** A song the app knows the beats of but does not bring along (dance/songs.json): the listener adds their own copy. */
class KnownSong(val key: String, val title: String, val artist: String, val bpm: Double, val duration: Double,
                val gridPath: String, val onsetsPath: String, val difficulty: String) {
    companion object {
        fun parseAll(text: String): List<KnownSong> {
            val a = Json.obj(text).arr("songs")
            return (0 until a.size).map { i ->
                val s = a.obj(i)
                KnownSong(s.str("key"), s.str("title"), s.optStr("artist", ""), s.num("bpm"), s.num("duration"), s.str("grid"), s.str("onsets"), s.optStr("difficulty", ""))
            }
        }
    }
}

/** Recognising a song among the listener's own files (user 2026-11-19: the chosen file "is hashed and compared to the
 *  songs that the app mapped out their steps (we need some simple hash or equivalent ... that is not broken by
 *  compression"). The "hash" is the song's onset curve. The file's curve is slid along each known song's; where the two
 *  agree, the file is that song, and the place of the best fit says how much later (or earlier) the music starts in
 *  the listener's copy, so that the song's beat grid can be moved onto it.
 *
 *  Measured on 2026-11-21 with the three bundled songs (24 copies: MP3, AAC and Opus down to 64 kbit/s, mono, 22 and
 *  44.1 kHz, quieter, silence added at the start, the start cut off): every copy scored 0.86 to 0.99 against its own
 *  song and at most 0.11 against another, and the start was found to within 1.2 ms. The same tune at another speed
 *  scored 0.07 at most. A shorter cut of a recording with the same beginning scores high in the first 90 s, which is
 *  why the whole length must line up too. */
object Onsets {
    const val FS = 48000              // every curve is made from the sound at this sampling rate
    const val N = 2048                // the window, in samples
    const val F_LO = 150.0
    const val F_HI = 6000.0
    const val COMPRESS = 1000.0
    const val DETREND = 51            // frames: half a second
    const val MAX_SHIFT = 15.0        // s: how much later or earlier the music may start in a copy
    const val WINDOW = 90.0           // s of the song that the search for the shift uses
    const val MIN_SCORE = 0.5         // how alike the two curves must be, there and over the whole song
    const val MIN_COVER = 0.9         // the copy must hold at least this much of the song
    const val LIKE_SCORE = 0.3        // from here on a file "sounds like" a song (two different songs stay under 0.15)

    /** The curve of some sound, made a few frames at a time so that a page can stay alive meanwhile: the same numbers
     *  as music/tools/onset_curve.py (tools/check_songs.py compares the two). `samples`: one channel, at `fs`. */
    class Meter(private val samples: FloatArray, val fs: Int = FS) {
        val hop = (fs / 100.0).roundToInt()
        val frames = if (samples.size >= N) 1 + (samples.size - N) / hop else 0
        var done = 0
            private set
        private val flux = FloatArray(frames)
        private val win = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / N) }   // the periodic Hann window
        private val lo = ceil(F_LO * N / fs).toInt()
        private val hi = min(floor(F_HI * N / fs).toInt(), N / 2)
        private val re = DoubleArray(N)
        private val im = DoubleArray(N)
        private var prev = DoubleArray(max(0, hi - lo + 1))
        private var cur = DoubleArray(max(0, hi - lo + 1))
        // the transform's tables: where each sample goes first, and the turns
        private val rev = IntArray(N)
        private val cosT = DoubleArray(N / 2) { cos(-2.0 * PI * it / N) }
        private val sinT = DoubleArray(N / 2) { sin(-2.0 * PI * it / N) }

        init {
            var bits = 0
            while ((1 shl bits) < N) bits++
            for (i in 0 until N) { var r = 0; for (b in 0 until bits) if ((i shr b) and 1 == 1) r = r or (1 shl (bits - 1 - b)); rev[i] = r }
        }

        /** make up to `count` more frames; true when the curve is complete */
        fun step(count: Int): Boolean {
            var left = count
            while (left-- > 0 && done < frames) { frame(done); done++ }
            return done >= frames
        }

        private fun frame(n: Int) {
            val start = n * hop
            for (i in 0 until N) { val j = rev[i]; re[j] = samples[start + i] * win[i]; im[j] = 0.0 }
            var len = 2
            while (len <= N) {
                val half = len / 2; val stride = N / len
                var i = 0
                while (i < N) {
                    var k = 0
                    for (j in 0 until half) {
                        val a = i + j; val b = a + half
                        val wr = cosT[k]; val wi = sinT[k]
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                        k += stride
                    }
                    i += len
                }
                len = len shl 1
            }
            var f = 0.0
            for (k in cur.indices) {
                val x = re[lo + k]; val y = im[lo + k]
                val c = ln(1.0 + COMPRESS * sqrt(x * x + y * y))
                cur[k] = c
                val d = c - prev[k]
                if (n > 0 && d > 0) f += d
            }
            val t = prev; prev = cur; cur = t
            flux[n] = f.toFloat()
        }

        /** the finished curve: the flux less its moving average, what is below that set to 0 */
        fun curve(): OnsetCurve = OnsetCurve(fs.toDouble() / hop, N / 2.0 / fs, detrend(flux))
    }

    /** minus the moving average over DETREND frames (at the edges: the average of what is there), negatives to 0 */
    fun detrend(f: FloatArray): FloatArray {
        val h = DETREND / 2
        val cs = DoubleArray(f.size + 1)
        for (i in f.indices) cs[i + 1] = cs[i] + f[i]
        return FloatArray(f.size) { i ->
            val a = max(0, i - h); val b = min(f.size, i + h + 1)
            max(0.0, f[i] - (cs[b] - cs[a]) / (b - a)).toFloat()
        }
    }

    /** the whole curve of some sound at once */
    fun curve(samples: FloatArray, fs: Int = FS): OnsetCurve = Meter(samples, fs).also { it.step(Int.MAX_VALUE) }.curve()

    /** How well a file fits a song. `score`: how alike the curves are over the song's first WINDOW seconds at the best
     *  shift (1 the same, 0 nothing in common); `shift`: how many seconds later the music starts in the file than in
     *  the song (negative: earlier, the file lacks the song's beginning); `whole`: how alike they are over all of the
     *  song that the file holds, at that shift; `cover`: how much of the song the file holds. */
    class Fit(val score: Double, val shift: Double, val whole: Double, val cover: Double) {
        val good: Boolean get() = score >= MIN_SCORE && whole >= MIN_SCORE && cover >= MIN_COVER
    }

    /** how alike a[a0 until a0 + m] and b[b0 until b0 + m] are: their correlation */
    private fun alike(a: FloatArray, a0: Int, b: FloatArray, b0: Int, m: Int): Double {
        if (m < 2) return 0.0
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (i in 0 until m) {
            val x = a[a0 + i].toDouble(); val y = b[b0 + i].toDouble()
            sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y
        }
        val va = saa - sa * sa / m; val vb = sbb - sb * sb / m
        return if (va > 1e-9 && vb > 1e-9) (sab - sa * sb / m) / sqrt(va * vb) else 0.0
    }

    /** slide the file's curve along the song's, MAX_SHIFT either way, and say how well it fits at the best place */
    fun fit(song: OnsetCurve, file: OnsetCurve): Fit {
        val rate = song.rate
        val r = song.values; val u = file.values
        val window = min(r.size, (WINDOW * rate).toInt())
        val most = (MAX_SHIFT * rate).toInt()
        val least = (20 * rate).toInt()            // fewer frames in common than this say nothing
        if (abs(file.rate - rate) > 1e-6 || window < least) return Fit(0.0, 0.0, 0.0, 0.0)
        val scores = DoubleArray(2 * most + 1) { Double.NaN }
        var best = -1
        for (k in scores.indices) {
            val lag = k - most                     // frame j of the song is frame j + lag of the file
            val u0 = max(0, lag); val r0 = max(0, -lag)
            val m = min(u.size - u0, window - r0)
            if (m < least) continue
            scores[k] = alike(u, u0, r, r0, m)
            if (best < 0 || scores[k] > scores[best]) best = k
        }
        if (best < 0) return Fit(0.0, 0.0, 0.0, 0.0)
        // the top of the peak lies between two frames: a parabola through the best place and its two neighbours
        var frac = 0.0
        if (best > 0 && best < scores.size - 1 && !scores[best - 1].isNaN() && !scores[best + 1].isNaN()) {
            val y0 = scores[best - 1]; val y1 = scores[best]; val y2 = scores[best + 1]
            val d = y0 - 2 * y1 + y2
            if (abs(d) > 1e-12) frac = (0.5 * (y0 - y2) / d).coerceIn(-0.5, 0.5)
        }
        val lag = best - most
        val u0 = max(0, lag); val r0 = max(0, -lag)
        val m = min(u.size - u0, r.size - r0)
        return Fit(scores[best], (lag + frac) / rate, alike(u, u0, r, r0, m), if (r.isEmpty()) 0.0 else m.toDouble() / r.size)
    }

    /** how a file fits every known song */
    fun fits(file: OnsetCurve, songs: List<Pair<KnownSong, OnsetCurve>>): List<Pair<KnownSong, Fit>> {
        val clipped = file.clipped()
        return songs.map { (s, c) -> Pair(s, fit(c, clipped)) }
    }

    /** the known song that a file is, and how it fits; null when it is none of them */
    fun recognise(file: OnsetCurve, songs: List<Pair<KnownSong, OnsetCurve>>): Pair<KnownSong, Fit>? = recognised(fits(file, songs))
    fun recognised(fits: List<Pair<KnownSong, Fit>>): Pair<KnownSong, Fit>? = fits.filter { it.second.good }.maxByOrNull { it.second.score }

    /** a known song that the file sounds like without being the mapped recording of it: another version, or the same
     *  one cut differently (its beginning fits, the rest does not); null when it sounds like none */
    fun soundsLike(fits: List<Pair<KnownSong, Fit>>): KnownSong? =
        fits.filter { !it.second.good && it.second.score >= LIKE_SCORE }.maxByOrNull { it.second.score }?.first
}
