package org.lior.sixstep

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
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
        fun load(ctx: Context, path: String): BeatGrid {
            val o = JSONObject(ctx.assets.open(path).bufferedReader().readText())
            val ba = o.getJSONArray("beats"); val beats = DoubleArray(ba.length()) { ba.getDouble(it) }
            val bb = o.optJSONArray("bars"); val bars = if (bb == null) DoubleArray(0) else DoubleArray(bb.length()) { bb.getDouble(it) }
            return BeatGrid(o.getDouble("bpm"), o.getDouble("beat"), o.optDouble("swing", 0.67), o.optDouble("duration", beats.last()), beats, bars)
        }
        /** the grid of a track (the beats annotated by ear in the beat annotator when that was done) */
        fun loadFor(ctx: Context, track: Track): BeatGrid = load(ctx, track.gridPath)
    }
}

/** A track time-stretched by `tempo` with its pitch kept: `offset` BPM faster (or slower, when negative) than the original. */
class TrackVariant(val offset: Int, val bpm: Double, val tempo: Double, val file: String)

class Track(val key: String, val title: String, val artist: String, val licence: String, val bpm: Double, val duration: Double, val file: String, val gridPath: String, val difficulty: String = "", val variants: List<TrackVariant> = emptyList()) {
    /** the variant `offset` BPM away from the original, or the nearest one there is */
    fun variantFor(offset: Int): TrackVariant = variants.minByOrNull { abs(it.offset - offset) } ?: TrackVariant(0, bpm, 1.0, file)

    companion object {
        fun loadAll(ctx: Context): List<Track> {
            val o = JSONObject(ctx.assets.open("dance/tracks.json").bufferedReader().readText())
            val a = o.getJSONArray("tracks")
            return (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                val bpm = t.getDouble("bpm"); val file = t.getString("file")
                // the tempo variants; older assets without them have just the track itself
                val variants = ArrayList<TrackVariant>()
                val va = t.optJSONArray("variants")
                if (va != null) for (k in 0 until va.length()) {
                    val v = va.getJSONObject(k); val off = v.getInt("offset")
                    variants.add(TrackVariant(off, v.optDouble("bpm", bpm + off), v.optDouble("tempo", (bpm + off) / bpm), v.optString("file", file)))
                }
                if (variants.none { it.offset == 0 }) variants.add(TrackVariant(0, bpm, 1.0, file))
                variants.sortBy { it.offset }
                Track(t.getString("key"), t.getString("title"), t.getString("artist"), t.getString("licence"), bpm, t.getDouble("duration"), file, t.getString("grid"), t.optString("difficulty", ""), variants)
            }
        }
    }
}

/** Maps the phone's clock to beats. */
interface BeatClock {
    fun beatNow(): Double
    /** uptime (ms) at which beat b happens, as far as this clock can tell */
    fun uptimeOfBeat(b: Double): Long
}

/** A steady tempo from a given uptime. */
class TempoClock(var bpm: Double, private var beat0: Double, private var uptime0: Long) : BeatClock {
    override fun beatNow(): Double = beat0 + (SystemClock.uptimeMillis() - uptime0) / 1000.0 * bpm / 60.0
    override fun uptimeOfBeat(b: Double): Long = uptime0 + ((b - beat0) * 60.0 / bpm * 1000.0).toLong()
    fun reanchor(b: Double, uptime: Long) { beat0 = b; uptime0 = uptime }
}

/** Beats read off a MediaPlayer through the track's grid: dance beat 0 = grid beat k0 + offset.
 *  The player's position is read a few times a second and the estimate between reads runs on the phone's clock, with
 *  small differences absorbed gently and big ones (a seek) taken at once, so the beat never jitters. */
class MusicClock(val player: MediaPlayer, val grid: BeatGrid, val k0: Int, val danceBeatOffset: Double) : BeatClock {
    private var estPos = Double.NaN; private var estUp = 0L; private var lastRead = 0L
    @Synchronized fun positionSec(): Double {
        val up = SystemClock.uptimeMillis()
        if (estPos.isNaN()) { estPos = safePos(); estUp = up; lastRead = up; return estPos }
        val run = estPos + (up - estUp) / 1000.0
        if (up - lastRead >= 100) {
            lastRead = up
            val read = safePos()
            val drift = read - run
            estPos = if (abs(drift) > 0.08) read else run + drift * 0.15
            estUp = up
            return estPos
        }
        return run
    }
    private fun safePos(): Double = try { player.currentPosition / 1000.0 } catch (e: Exception) { estPos }
    @Synchronized fun resync() { estPos = Double.NaN }
    override fun beatNow(): Double = grid.beatAt(positionSec() - grid.offset) - k0 + danceBeatOffset
    override fun uptimeOfBeat(b: Double): Long {
        val s = grid.beatSeconds(b - danceBeatOffset + k0) + grid.offset
        return SystemClock.uptimeMillis() + ((s - positionSec()) * 1000.0).toLong()
    }
}

/** Another clock plus an offset that fades to zero over `fadeMs`: a hand-over without a jump. */
class SlewClock(val inner: BeatClock, private val offset0: Double, private val fadeMs: Long) : BeatClock {
    private val start = SystemClock.uptimeMillis()
    private fun offsetNow(): Double { val u = ((SystemClock.uptimeMillis() - start).toDouble() / fadeMs).coerceIn(0.0, 1.0); return offset0 * (1 - u) }
    override fun beatNow(): Double = inner.beatNow() + offsetNow()
    override fun uptimeOfBeat(b: Double): Long = inner.uptimeOfBeat(b - offsetNow())
}

/** Snare on the odd counts (1, 3, 5), closed hi-hat on their swung "&" and on the even counts (2, 4, 6); either drum can be
 *  switched off. Runs on its own thread so the drawing cannot delay a hit. */
class Metronome(ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
    private val snare = pool.load(ctx, R.raw.snare, 1)
    private val hihat = pool.load(ctx, R.raw.hihat, 1)
    private val thread = HandlerThread("metronome", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile var clock: BeatClock? = null
    @Volatile var swing = 0.67
    @Volatile var enabled = false
    @Volatile var volume = 1.0f
    @Volatile var snareOn = true
    @Volatile var hatOn = true
    private var nextEvent = Double.NaN
    @Volatile private var running = false
    private var scheduledUntil = 0L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val c = clock
            if (c != null && enabled) {
                val b = c.beatNow()
                if (nextEvent.isNaN() || nextEvent < b - 1.0) nextEvent = nextEventAfter(b - 0.01)
                var guard = 0
                // schedule every hit that falls within the next 120 ms at its exact uptime
                while (guard++ < 8) {
                    val at = c.uptimeOfBeat(nextEvent)
                    val now = SystemClock.uptimeMillis()
                    if (at > now + 120) break
                    val isSnare = isSnareEvent(nextEvent)
                    // a drum that is switched off is simply not scheduled; the loop keeps its timing either way
                    if (if (isSnare) snareOn else hatOn) handler.postAtTime({ pool.play(if (isSnare) snare else hihat, volume, volume, 1, 0, 1f) }, maxOf(at, now))
                    nextEvent = nextEventAfter(nextEvent)
                }
            }
            handler.postDelayed(this, 20)
        }
    }

    /** events per two beats starting at an even beat e: e (snare), e + swing (hat), e + 1 (hat) */
    private fun nextEventAfter(b: Double): Double {
        val e = floor(b / 2.0) * 2.0
        val cands = doubleArrayOf(e, e + swing, e + 1.0, e + 2.0, e + 2.0 + swing, e + 3.0)
        for (c in cands) if (c > b + 1e-9) return c
        return e + 4.0
    }
    private fun isSnareEvent(b: Double): Boolean { val r = b - floor(b / 2.0) * 2.0; return abs(r) < 1e-6 }

    fun start() { if (!running) { running = true; nextEvent = Double.NaN; handler.post(loop) } }
    fun stop() { running = false; handler.removeCallbacksAndMessages(null); nextEvent = Double.NaN }
    /** forget the scheduled hits (after a seek or a clock change) */
    fun resync() { handler.removeCallbacksAndMessages(null); nextEvent = Double.NaN; if (running) handler.post(loop) }
    fun release() { stop(); pool.release(); thread.quitSafely() }
}

/** Reads annotations aloud: Google Translate's speech endpoint, cached per phrase; the phone's own voice when offline.
 *  Tells its owner when it is speaking (onSpeaking, on the main thread) so the music can come down a little under the
 *  voice: true as a phrase starts, false when it is over, cut, failed, or at the latest when a watchdog gives up on it. */
class Speaker(private val ctx: Context) {
    companion object {
        const val WAIT_MS = 1200L      // a phrase still downloading is waited for this long before the phone's voice reads it
        const val CLIP_VOLUME = 0.8f   // the clips peak 3 dB under full scale: with the music ducked, music plus voice stays under it
        private val LEAD = Regex("\\blead\\b", RegexOption.IGNORE_CASE)
    }

    private var ready = false
    private lateinit var tts: TextToSpeech

    /** how a phrase is said: words the voices read wrongly are spelled the way they sound. "lead" alone reads as the metal
     *  ("led"); "leed" reads as in "lead and follow" (user 2026-10-19). The screen keeps the real spelling. */
    private fun spoken(text: String): String = text.replace(LEAD, "leed")
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    /** the voice sits in the same volume group as the music; not an assistant or a navigation prompt, which some phones treat specially */
    private val speechAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    /** the owner's hook: true when a phrase starts, false when it is over; always on the main thread, once per change */
    var onSpeaking: ((Boolean) -> Unit)? = null
    private var speaking = false
    private var generation = 0                        // bumped by every speak() and stop(): whatever an earlier phrase still posts is ignored
    private var watchdog: Runnable? = null
    private val inFlight = HashMap<String, Thread>()  // the downloads under way, by phrase

    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.US
                tts.setAudioAttributes(speechAttrs)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { over(utteranceId) }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { over(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { over(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { over(utteranceId) }
                })
            }
        }
    }

    /** the phone's voice finished (or was cut off); the utterance id carries the generation it belongs to. Any thread. */
    private fun over(utteranceId: String?) {
        val gen = utteranceId?.removePrefix("6step-")?.toIntOrNull() ?: return
        handler.post { if (gen == generation) setSpeaking(false) }
    }

    private fun setSpeaking(v: Boolean) { if (speaking != v) { speaking = v; onSpeaking?.invoke(v) } }

    /** the music is back at the latest `ms` from now, whatever becomes of the phrase */
    private fun armWatchdog(gen: Int, ms: Long) {
        watchdog?.let { handler.removeCallbacks(it) }
        val r = Runnable { watchdog = null; if (gen == generation) setSpeaking(false) }
        watchdog = r; handler.postDelayed(r, ms)
    }

    private fun cacheFile(text: String): File {
        val md = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return File(ctx.cacheDir, "tts_" + md.joinToString("") { "%02x".format(it) } + ".mp3")
    }
    private fun cached(text: String): Boolean = cacheFile(text).let { it.exists() && it.length() > 500 }

    private fun download(text: String): Boolean {
        val f = cacheFile(text)
        if (f.exists() && f.length() > 500) return true
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=en&q=$q")
            val con = url.openConnection() as HttpURLConnection
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            con.setRequestProperty("Referer", "https://translate.google.com/")
            con.connectTimeout = 6000; con.readTimeout = 10000
            val ok = con.responseCode == 200 && (con.contentType ?: "").contains("audio")
            if (ok) {
                val bytes = con.inputStream.readBytes()
                if (bytes.size > 500) {
                    // written whole and then renamed into place: a clip is either there or not, never half of one
                    val part = File(f.path + "." + Thread.currentThread().id + ".part")
                    part.writeBytes(bytes)
                    if (part.renameTo(f)) true else { part.delete(); f.exists() && f.length() > 500 }
                } else false
            } else false
        } catch (e: Exception) { false }
    }

    /** the download of a phrase on its own thread: the one under way, when there is one, so nothing is fetched twice */
    private fun fetch(text: String): Thread = synchronized(inFlight) {
        inFlight[text]?.let { return it }
        val t = object : Thread() {
            override fun run() { try { download(text) } finally { synchronized(inFlight) { if (inFlight[text] === this) inFlight.remove(text) } } }
        }
        inFlight[text] = t; t.start(); t
    }

    /** fetch the phrases that are not cached yet, in the background, one at a time in the order given */
    fun prefetch(texts: Collection<String>) {
        val todo = texts.map { spoken(it) }.distinct().filter { it.isNotBlank() && !cached(it) }
        if (todo.isEmpty()) return
        Thread { for (t in todo) if (!cached(t)) fetch(t).join() }.start()
    }

    fun speak(phrase: String) {
        if (phrase.isBlank()) return
        val text = spoken(phrase)
        val gen = ++generation
        stopCurrent()
        if (cached(text) && playClip(text, gen)) return
        // not cached yet: its download gets up to WAIT_MS (the banner stays for three beats), then the phone's voice reads it
        val t = fetch(text)
        Thread {
            try { t.join(WAIT_MS) } catch (e: InterruptedException) {}
            handler.post { if (gen == generation && !(cached(text) && playClip(text, gen))) speakWithEngine(text, gen) }
        }.start()
    }

    /** the cached clip of a phrase; false when the file will not play (it is dropped, to be fetched afresh) */
    private fun playClip(text: String, gen: Int): Boolean {
        val f = cacheFile(text)
        var mp: MediaPlayer? = null
        return try {
            val p = MediaPlayer(); mp = p
            p.setAudioAttributes(speechAttrs)
            p.setDataSource(f.absolutePath); p.prepare()
            p.setVolume(CLIP_VOLUME, CLIP_VOLUME)
            p.setOnCompletionListener { done(it, gen) }
            p.setOnErrorListener { e, _, _ -> done(e, gen); true }
            p.start()
            player = p
            setSpeaking(true)
            armWatchdog(gen, (if (p.duration > 0) p.duration else 4000) + 500L)
            true
        } catch (e: Exception) { try { mp?.release() } catch (e2: Exception) {}; f.delete(); false }
    }

    /** a clip ended (or failed): let it go and, if it is still the phrase being spoken, bring the music back */
    private fun done(p: MediaPlayer, gen: Int) {
        try { p.release() } catch (e: Exception) {}
        if (player === p) player = null
        if (gen == generation) setSpeaking(false)
    }

    /** the phone's own voice, the fallback; the watchdog allows about 15 characters a second plus a margin */
    private fun speakWithEngine(text: String, gen: Int) {
        if (gen != generation || !ready) return
        setSpeaking(true); armWatchdog(gen, text.length * 90L + 1500L)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "6step-$gen") != TextToSpeech.SUCCESS) setSpeaking(false)
    }

    /** silence at once; a phrase still waiting for its download is dropped too */
    fun stop() { generation++; stopCurrent() }

    private fun stopCurrent() {
        watchdog?.let { handler.removeCallbacks(it) }; watchdog = null
        player?.let { try { it.stop() } catch (e: Exception) {}; try { it.release() } catch (e: Exception) {} }; player = null
        if (ready) try { tts.stop() } catch (e: Exception) {}
        setSpeaking(false)
    }

    fun release() { stop(); onSpeaking = null; ready = false; handler.removeCallbacksAndMessages(null); tts.shutdown() }
}

/** Plays music from the assets. Two players at once: the one the clock follows and one cued on another file (the next
 *  track, or the same track at another tempo) that takes over on a beat while the first fades out. */
class MusicPlayer(private val ctx: Context) {
    /** the player the music clock follows */
    var player: MediaPlayer? = null
        private set
    /** cued on another file, not started yet */
    var next: MediaPlayer? = null
        private set
    /** the asset file `next` is cued on */
    var nextPath: String? = null
        private set
    var onComplete: (() -> Unit)? = null
    /** 1 normally, lower while an annotation is read aloud: multiplies every player's own level (the crossfades) */
    var duckLevel = 1f
        private set
    private val level = HashMap<MediaPlayer, Float>()   // each player's own level, before the duck

    private fun open(path: String): MediaPlayer {
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        val afd = ctx.assets.openFd(path)
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.prepare()
        level[mp] = 1f; applyVolume(mp)
        return mp
    }

    /** cue an asset file on the second player, ready to seek and start; whatever was cued before is dropped */
    fun prepareNext(path: String): MediaPlayer { dropNext(); val mp = open(path); next = mp; nextPath = path; return mp }
    /** the cued player becomes the one the clock follows; the previous one is handed back, still playing, to be faded
     *  out and released by the caller. Only the current player reports the end of the music. */
    fun swapToNext(): MediaPlayer? {
        val n = next ?: return null
        val old = player
        old?.setOnCompletionListener(null)
        n.setOnCompletionListener { if (player === it) onComplete?.invoke() }
        player = n; next = null; nextPath = null
        return old
    }
    fun dropNext() { next?.let { releasePlayer(it) }; next = null; nextPath = null }

    fun seekTo(seconds: Double) { player?.let { seekTo(it, seconds) } }
    /** an exact seek (to the sample, not the nearest frame), so the beat is where the grid says */
    fun seekTo(mp: MediaPlayer, seconds: Double) {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
        try { mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST) } catch (e: Exception) { try { mp.seekTo(ms.toInt()) } catch (e2: Exception) {} }
    }
    fun startAt(seconds: Double) { val mp = player ?: return; seekTo(mp, seconds); mp.start() }
    fun pause() { player?.let { if (playing(it)) it.pause() } }
    fun resume() { player?.let { if (!playing(it)) it.start() } }
    fun isPlaying(): Boolean = player?.let { playing(it) } == true
    fun playing(mp: MediaPlayer): Boolean = try { mp.isPlaying } catch (e: Exception) { false }
    /** a player's own level (0..1); what is heard is this times the duck */
    fun setVolume(mp: MediaPlayer, v: Float) { level[mp] = v; applyVolume(mp) }
    /** every player at `factor` of its own level: the music under a spoken annotation; 1 brings it all back */
    fun duck(factor: Float) { duckLevel = factor.coerceIn(0f, 1f); for (mp in level.keys) applyVolume(mp) }
    private fun applyVolume(mp: MediaPlayer) { val v = (level[mp] ?: 1f) * duckLevel; try { mp.setVolume(v, v) } catch (e: Exception) {} }
    fun releasePlayer(mp: MediaPlayer) { level.remove(mp); try { mp.stop() } catch (e: Exception) {}; try { mp.release() } catch (e: Exception) {} }
    fun release() { dropNext(); player?.let { releasePlayer(it) }; player = null }
}
