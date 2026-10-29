package org.lior.sixstep

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lining up what the phone hears with the song (the Spotify test page's "Listen", 2026-10-29). Spotify's reported
 *  position jumps by up to ~350 ms at every play; the music itself does not lie. The app listens (the microphone, and
 *  where Android and Spotify allow it the phone's own output), turns the sound into an onset curve exactly as
 *  music/tools/onset_curve.py did for the shipped curve, and slides the two against each other: the best lag is how far
 *  the sound heard is from where Spotify says it is. Tested in Python on the song with simulated room noise
 *  (97% within 20 ms at 5 dB SNR); nothing of the sound is kept, only its rhythm. */

/** the shipped onset curve: one value per 10 ms frame, frame 0 centred at t0 seconds */
class OnsetCurve(val rate: Double, val t0: Double, val values: FloatArray) {
    fun at(s: Double): Float {
        val x = (s - t0) * rate
        val i = floor(x).toInt()
        if (i < 0 || i + 1 >= values.size) return 0f
        val f = (x - i).toFloat()
        return values[i] * (1f - f) + values[i + 1] * f
    }

    companion object {
        fun load(ctx: Context, path: String): OnsetCurve {
            val o = JSONObject(ctx.assets.open(path).bufferedReader().readText())
            val a = o.getJSONArray("values")
            return OnsetCurve(o.getDouble("rate"), o.getDouble("t0"), FloatArray(a.length()) { a.getInt(it).toFloat() })
        }
    }
}

/** spectral flux, frame by frame, the same numbers as music/tools/onset_curve.py (checked against it in Python):
 *  a periodic Hann window of 2048 samples every fs/100, log(1 + 1000 |X|) from 150 Hz to 6 kHz, the rises summed */
class FluxMeter(val fs: Int) {
    companion object {
        const val N = 2048
        const val DETREND = 51

        /** minus the moving average over 51 frames (edges: the average of what is there), negatives to 0 */
        fun detrend(f: FloatArray): FloatArray {
            val h = DETREND / 2
            val cs = DoubleArray(f.size + 1)
            for (i in f.indices) cs[i + 1] = cs[i] + f[i]
            return FloatArray(f.size) { i ->
                val a = max(0, i - h); val b = min(f.size, i + h + 1)
                max(0.0, f[i] - (cs[b] - cs[a]) / (b - a)).toFloat()
            }
        }

        /** in place, radix 2 */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val tr = re[b] * cr - im[b] * ci
                        val ti = re[b] * ci + im[b] * cr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    val hop = (fs / 100.0).roundToInt()
    private val win = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / N) }
    private val lo = ceil(150.0 * N / fs).toInt()
    private val hi = floor(6000.0 * N / fs).toInt()
    private val ring = FloatArray(N)
    private var filled = 0L
    private var nextEnd = N.toLong()
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private var prev: DoubleArray? = null
    /** every frame: its flux, the sample index of its centre (counted from the first sample fed), its mean square */
    var onFrame: ((Float, Long, Float) -> Unit)? = null

    fun feed(s: ShortArray, count: Int) {
        for (i in 0 until count) {
            ring[(filled % N).toInt()] = s[i] / 32768f
            filled++
            if (filled == nextEnd) { frame(filled - N); nextEnd += hop }
        }
    }

    private fun frame(start: Long) {
        var ms = 0.0
        for (i in 0 until N) {
            val x = ring[((start + i) % N).toInt()].toDouble()
            ms += x * x
            re[i] = x * win[i]; im[i] = 0.0
        }
        fft(re, im)
        val cur = DoubleArray(hi - lo + 1) { ln1p(1000.0 * hypot(re[lo + it], im[lo + it])) }
        var f = 0.0
        val p = prev
        if (p != null) for (k in cur.indices) { val d = cur[k] - p[k]; if (d > 0) f += d }
        prev = cur
        onFrame?.invoke(f.toFloat(), start + N / 2, (ms / N).toFloat())
    }
}

/** the best lag (seconds) and its correlation, and the best one at least 100 ms away from it (the wrong-beat risk) */
class Estimate(val lag: Double, val r: Double, val lag2: Double, val r2: Double)

object OnsetMath {
    /** L: the detrended live curve; q: where Spotify says the song is at each frame (seconds). The song heard at frame
     *  j is at q[j] + lag. Lags -0.8 .. +0.8 s in 5 ms steps, Pearson correlation, as tested in Python. */
    fun estimate(L: FloatArray, q: DoubleArray, curve: OnsetCurve): Estimate {
        val n = L.size
        var mean = 0.0
        for (x in L) mean += x
        mean /= n
        val lc = DoubleArray(n) { L[it] - mean }
        var sl = 0.0
        for (x in lc) sl += x * x
        sl = sqrt(sl)
        val steps = 321
        val rs = DoubleArray(steps)
        val v = DoubleArray(n)
        for (s in 0 until steps) {
            val lag = -0.8 + s * 0.005
            var vm = 0.0
            for (j in 0 until n) { v[j] = curve.at(q[j] + lag).toDouble(); vm += v[j] }
            vm /= n
            var sv = 0.0; var cov = 0.0
            for (j in 0 until n) { val d = v[j] - vm; sv += d * d; cov += lc[j] * d }
            rs[s] = if (sl > 0 && sv > 0) cov / (sl * sqrt(sv)) else 0.0
        }
        var b = 0
        for (s in 1 until steps) if (rs[s] > rs[b]) b = s
        var b2 = -1
        for (s in 0 until steps) if (abs(s - b) * 0.005 > 0.1 && (b2 < 0 || rs[s] > rs[b2])) b2 = s
        if (b2 < 0) b2 = b
        return Estimate(-0.8 + b * 0.005, rs[b], -0.8 + b2 * 0.005, rs[b2])
    }
}

/** One source of sound (the microphone, or the phone's output): reads 20 ms at a time, keeps the last 6 s of onset
 *  curve with each frame's time on the phone's clock, and every 2 s (once 3 s are in) lines them up with the song. */
class Listener(val source: String, private val detail: String, private val make: () -> AudioRecord) : Thread("6step-listen-$source") {
    @Volatile var running = true

    override fun run() {
        val rec = try { make() } catch (e: Throwable) {
            AudioSync.failed(source, "${e.javaClass.simpleName}: ${e.message}"); return
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) { AudioSync.failed(source, "AudioRecord state ${rec.state}"); return }
            val fs = rec.sampleRate
            val meter = FluxMeter(fs)
            val cap = 600
            val times = LongArray(cap); val flux = FloatArray(cap); val pow = FloatArray(cap)
            var count = 0; var bufGen = -1; var sinceEst = 0; var silentGen = -1
            var anchorFrame = 0L; var anchorNs = 0L; var hw = false; var lastTs = 0L; var total = 0L
            val ts = AudioTimestamp()

            fun evaluate(g: Int) {
                val curve = AudioSync.curve ?: return
                val s = AudioSync.spot
                var sum = 0.0
                for (i in 0 until count) sum += pow[i]
                val rms = sqrt(sum / count)
                if (rms < 1e-4) {   // nothing heard: the mic is muted in the background, or Spotify blocks capture
                    if (silentGen != g) { silentGen = g; AudioSync.silent(source, g, rms) }
                    return
                }
                val live = FluxMeter.detrend(flux.copyOf(count))
                val q = DoubleArray(count) { AudioSync.songAt(s, times[it]) }
                AudioSync.result(source, g, OnsetMath.estimate(live, q, curve), count, rms, if (hw) "hw" else "read")
            }

            meter.onFrame = { f, centre, ms ->
                val s = AudioSync.spot; val g = AudioSync.gen
                if (!s.playing) { count = 0; bufGen = g; sinceEst = 0 }
                else {
                    if (g != bufGen) { count = 0; bufGen = g; sinceEst = 0 }
                    if (count == cap) {
                        System.arraycopy(times, 1, times, 0, cap - 1); System.arraycopy(flux, 1, flux, 0, cap - 1)
                        System.arraycopy(pow, 1, pow, 0, cap - 1); count--
                    }
                    times[count] = anchorNs + ((centre - anchorFrame) * 1e9 / fs).toLong()
                    flux[count] = f; pow[count] = ms; count++; sinceEst++
                    if (count >= 300 && sinceEst >= 200) { sinceEst = 0; evaluate(g) }
                }
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { AudioSync.failed(source, "did not start recording"); return }
            AudioSync.started(source, "$detail, $fs Hz")
            val buf = ShortArray(fs / 50)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { AudioSync.failed(source, "read error $n"); break }
                if (n == 0) continue
                total += n
                val now = SystemClock.elapsedRealtimeNanos()
                // when each sample was taken, on the clock Spotify's position is given in (BOOTTIME = elapsedRealtime)
                if (now - lastTs > 1_000_000_000L) {
                    lastTs = now
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        anchorFrame = ts.framePosition; anchorNs = ts.nanoTime; hw = true
                    } else if (!hw) { anchorFrame = total; anchorNs = now }
                }
                meter.feed(buf, n)
            }
        } catch (e: Throwable) {
            AudioSync.failed(source, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { rec.stop() } catch (e: Exception) {}
            rec.release()
        }
    }
}

/** The shared state: Spotify's position as the page last saw it, which "window" of steady playing we are in (a new one
 *  at every play, pause or jump of Spotify's anchor), each source's estimates, and the correction in use: a source's
 *  correction counts once two estimates in a row in the same window agree within 20 ms (one alone may be a beat off). */
object AudioSync {
    class Spot(val playing: Boolean, val posMs: Long, val updMs: Long, val speed: Float)

    @Volatile var curve: OnsetCurve? = null
    @Volatile var spot = Spot(false, 0, 0, 0f)
    @Volatile var gen = 0
    /** a row's note for the page's table (called on a listener thread) */
    @Volatile var onEvent: ((String) -> Unit)? = null
    private var anchor = Long.MIN_VALUE
    private var mic: Listener? = null
    private var capture: Listener? = null
    private val last = HashMap<String, Pair<Int, Double>>()
    private val applied = HashMap<String, Pair<Int, Double>>()
    private val status = LinkedHashMap<String, String>()

    @Synchronized fun spotify(playing: Boolean, posMs: Long, updMs: Long, speed: Float) {
        val a = posMs - updMs
        if (playing != spot.playing || (playing && abs(a - anchor) > 20)) gen++
        anchor = a
        spot = Spot(playing, posMs, updMs, speed)
    }

    /** where Spotify says the song is (seconds) at an elapsedRealtime in nanoseconds */
    fun songAt(s: Spot, tNs: Long): Double = (s.posMs + (tNs / 1e6 - s.updMs) * s.speed) / 1000.0

    /** the correction in use (seconds, add it to Spotify's position) and its source: the microphone first */
    @Synchronized fun correction(): Pair<String, Double>? {
        for (src in listOf("mic", "capture")) { val a = applied[src]; if (a != null && a.first == gen) return Pair(src, a.second) }
        return null
    }

    @Synchronized fun statusText(): String = status.entries.joinToString(" · ") { "${it.key}: ${it.value}" }

    fun result(source: String, g: Int, e: Estimate, frames: Int, rms: Double, ts: String) {
        var use = false
        synchronized(this) {
            val p = last[source]
            if (p != null && p.first == g && g == gen && abs(p.second - e.lag) <= 0.02 && e.r >= 0.2) {
                applied[source] = Pair(g, (p.second + e.lag) / 2); use = true
            }
            last[source] = Pair(g, e.lag)
            val inUse = applied[source]?.takeIf { it.first == gen }
            status[source] = String.format(Locale.US, "%+.0f ms (r %.2f)%s", e.lag * 1000, e.r, if (inUse != null) ", in use" else "")
        }
        event(String.format(Locale.US, "source=%s;event=estimate;e_ms=%.0f;r=%.3f;e2_ms=%.0f;r2=%.3f;frames=%d;rms=%.5f;ts=%s;window=%d;applied=%s",
            source, e.lag * 1000, e.r, e.lag2 * 1000, e.r2, frames, rms, ts, g, use))
    }

    fun silent(source: String, g: Int, rms: Double) {
        synchronized(this) { status[source] = if (source == "capture") "silent (Spotify blocks it?)" else "silent (app in the background?)" }
        event(String.format(Locale.US, "source=%s;event=silent;rms=%.6f;window=%d", source, rms, g))
    }

    fun started(source: String, detail: String) {
        synchronized(this) { status[source] = "listening" }
        event("source=$source;event=started;detail=$detail")
    }

    fun failed(source: String, why: String) {
        synchronized(this) { status[source] = "failed" }
        event("source=$source;event=failed;error=${why.replace(';', ',')}")
    }

    fun event(note: String) { onEvent?.invoke(note) }

    /** the microphone, with as little processing as the phone offers: echo cancelling would remove the very music */
    @SuppressLint("MissingPermission")
    @Synchronized fun startMic(ctx: Context) {
        if (mic != null) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val raw = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val src = if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val fs = 48000
        val size = max(AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), fs / 5 * 2) * 4
        status["mic"] = "starting"
        mic = Listener("mic", if (raw) "UNPROCESSED" else "VOICE_RECOGNITION") {
            AudioRecord(src, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        }.also { it.start() }
    }

    /** the phone's own output (Android 10+, with the user's consent); apps like Spotify may opt out, then it is silent */
    @SuppressLint("MissingPermission")
    @RequiresApi(29)
    @Synchronized fun startCapture(p: MediaProjection) {
        if (capture != null) return
        val fs = 48000
        val size = max(AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), fs / 5 * 2) * 4
        status["capture"] = "starting"
        capture = Listener("capture", "playback capture") {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(fs)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(size)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        }.also { it.start() }
    }

    @Synchronized fun stopCapture() {
        capture?.running = false; capture = null
        status.remove("capture"); applied.remove("capture"); last.remove("capture")
    }

    @Synchronized fun stopAll() {
        mic?.running = false; mic = null
        stopCapture()
        status.clear(); applied.clear(); last.clear()
    }
}
