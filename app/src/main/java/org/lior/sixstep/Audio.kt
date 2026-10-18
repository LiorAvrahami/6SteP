package org.lior.sixstep

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
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
    /** the beat index where the dance starts: the first bar that begins at or after 3 s */
    fun startBeatIndex(): Int {
        val barT = bars.firstOrNull { it >= 3.0 } ?: beats[0]
        var k0 = 0
        for (i in beats.indices) if (abs(beats[i] - barT) < abs(beats[k0] - barT)) k0 = i
        return k0
    }
    companion object {
        fun load(ctx: Context, path: String): BeatGrid {
            val o = JSONObject(ctx.assets.open(path).bufferedReader().readText())
            val ba = o.getJSONArray("beats"); val beats = DoubleArray(ba.length()) { ba.getDouble(it) }
            val bb = o.optJSONArray("bars"); val bars = if (bb == null) DoubleArray(0) else DoubleArray(bb.length()) { bb.getDouble(it) }
            return BeatGrid(o.getDouble("bpm"), o.getDouble("beat"), o.optDouble("swing", 0.67), o.optDouble("duration", beats.last()), beats, bars)
        }
    }
}

class Track(val key: String, val title: String, val artist: String, val licence: String, val bpm: Double, val duration: Double, val file: String, val gridPath: String) {
    companion object {
        fun loadAll(ctx: Context): List<Track> {
            val o = JSONObject(ctx.assets.open("dance/tracks.json").bufferedReader().readText())
            val a = o.getJSONArray("tracks")
            return (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                Track(t.getString("key"), t.getString("title"), t.getString("artist"), t.getString("licence"), t.getDouble("bpm"), t.getDouble("duration"), t.getString("file"), t.getString("grid"))
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

/** Beats read off a MediaPlayer through the track's grid: dance beat 0 = grid beat k0 + offset. */
class MusicClock(val player: MediaPlayer, val grid: BeatGrid, val k0: Int, val danceBeatOffset: Double) : BeatClock {
    private var lastPos = 0.0; private var lastUp = 0L
    fun positionSec(): Double {
        val up = SystemClock.uptimeMillis()
        if (up - lastUp > 40) { lastPos = player.currentPosition / 1000.0; lastUp = up }
        return lastPos + (up - lastUp) / 1000.0
    }
    override fun beatNow(): Double = grid.beatAt(positionSec()) - k0 + danceBeatOffset
    override fun uptimeOfBeat(b: Double): Long {
        val s = grid.beatSeconds(b - danceBeatOffset + k0)
        return SystemClock.uptimeMillis() + ((s - positionSec()) * 1000.0).toLong()
    }
}

/** Thump on the odd counts (1, 3, 5), tick on their swung "&" and on the even counts (2, 4, 6). */
class Metronome(ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
    private val thump = pool.load(ctx, R.raw.thump, 1)
    private val tick = pool.load(ctx, R.raw.tick, 1)
    private val handler = Handler(Looper.getMainLooper())
    var clock: BeatClock? = null
    var swing = 0.67
    var enabled = false
    var volume = 1.0f
    private var nextEvent = Double.NaN
    private var running = false

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val c = clock
            if (c != null && enabled) {
                val b = c.beatNow()
                if (nextEvent.isNaN() || nextEvent < b - 1.0) nextEvent = nextEventAfter(b - 0.02)
                var guard = 0
                while (nextEvent <= b + 0.03 && guard++ < 8) {
                    val delay = (c.uptimeOfBeat(nextEvent) - SystemClock.uptimeMillis()).coerceIn(0L, 60L)
                    val isThump = isThumpEvent(nextEvent)
                    handler.postDelayed({ pool.play(if (isThump) thump else tick, volume, volume, 1, 0, 1f) }, delay)
                    nextEvent = nextEventAfter(nextEvent)
                }
            }
            handler.postDelayed(this, 15)
        }
    }

    /** events per two beats starting at an even beat e: e (thump), e + swing (tick), e + 1 (tick) */
    private fun nextEventAfter(b: Double): Double {
        val e = floor(b / 2.0) * 2.0
        val cands = doubleArrayOf(e, e + swing, e + 1.0, e + 2.0, e + 2.0 + swing, e + 3.0)
        for (c in cands) if (c > b + 1e-9) return c
        return e + 4.0
    }
    private fun isThumpEvent(b: Double): Boolean { val r = b - floor(b / 2.0) * 2.0; return abs(r) < 1e-6 }

    fun start() { if (!running) { running = true; nextEvent = Double.NaN; handler.post(loop) } }
    fun stop() { running = false; handler.removeCallbacks(loop); nextEvent = Double.NaN }
    fun resync() { nextEvent = Double.NaN }
    fun release() { stop(); pool.release() }
}

/** Reads annotations aloud: Google Translate's speech endpoint, cached per phrase; the phone's own voice when offline. */
class Speaker(private val ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.US
    }
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun cacheFile(text: String): File {
        val md = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return File(ctx.cacheDir, "tts_" + md.joinToString("") { "%02x".format(it) } + ".mp3")
    }

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
                if (bytes.size > 500) { f.writeBytes(bytes); true } else false
            } else false
        } catch (e: Exception) { false }
    }

    /** fetch the phrases that are not cached yet, in the background */
    fun prefetch(texts: Collection<String>) {
        val todo = texts.filter { it.isNotBlank() && !cacheFile(it).exists() }
        if (todo.isEmpty()) return
        Thread { for (t in todo) download(t) }.start()
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val f = cacheFile(text)
        if (f.exists() && f.length() > 500) {
            try {
                player?.release()
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(f.absolutePath); mp.prepare(); mp.setOnCompletionListener { it.release(); if (player === it) player = null }
                mp.start(); player = mp
                return
            } catch (e: Exception) { /* fall through to the phone's voice */ }
        }
        Thread { download(text) }.start()
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "6step")
    }

    fun stop() { try { player?.stop() } catch (e: Exception) {}; player?.release(); player = null; if (ready) tts.stop() }
    fun release() { stop(); tts.shutdown() }
}

/** Plays a track from the assets. */
class MusicPlayer(private val ctx: Context) {
    var player: MediaPlayer? = null
        private set
    var onComplete: (() -> Unit)? = null

    fun prepare(track: Track): MediaPlayer {
        release()
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        val afd = ctx.assets.openFd(track.file)
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        mp.prepare()
        mp.setOnCompletionListener { onComplete?.invoke() }
        player = mp
        return mp
    }

    fun startAt(seconds: Double) { val mp = player ?: return; mp.seekTo((seconds * 1000).toInt()); mp.start() }
    fun pause() { player?.let { if (it.isPlaying) it.pause() } }
    fun resume() { player?.let { if (!it.isPlaying) it.start() } }
    fun isPlaying(): Boolean = player?.isPlaying == true
    fun release() { try { player?.stop() } catch (e: Exception) {}; player?.release(); player = null }
}
